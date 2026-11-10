package dev.overhaul.minecraft.blaze;

/**
 * The compute shader that lights the finished world with ray queries. It rebuilds each pixel's camera-relative
 * position from depth and its normal from the neighbouring depths, then traces toward the sun or moon for shadows,
 * a few short rays for ambient occlusion and, on upward facing water, one reflected ray shaded from the hit
 * triangle's own vertices and the block atlas. Cutout geometry is alpha tested against the atlas while rays search.
 */
final class BlazeRayShader {
    static final int SHADOWS = 1, AMBIENT_OCCLUSION = 2, REFLECTIONS = 4, MOVING_NOISE = 8, SHOW_LIGHT = 16;
    static final int PUSH_BYTES = 128;

    static final String SOURCE = """
            #version 460
            #extension GL_EXT_ray_query : require
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_buffer_reference_uvec2 : require
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform accelerationStructureEXT scene;
            layout(binding = 1) uniform sampler2D sceneColor;
            layout(binding = 2) uniform sampler2D sceneDepth;
            layout(binding = 3) uniform sampler2D atlas;
            layout(binding = 4, rgba8) writeonly uniform image2D lit;
            layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Words { uint w[]; };
            layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Table { uvec4 e[]; };
            // light: direction to the sun or moon, then its strength; sky: the sky colour, then daylight;
            // size: pixels, then the hit table's address; options: flags, frame, traced distance's bits.
            layout(push_constant) uniform Frame { mat4 inverseViewProjection; vec4 light; vec4 sky; uvec4 size; uvec4 options; };

            const uint SOLID_AND_CUTOUT = 3u, TRANSLUCENT = 4u;

            struct Surface { vec2 uv; vec4 color; vec2 lightmap; vec3 normal; };

            uint indexAt(uvec2 address, uint bytes, uint position) {
                uint start = (address.x & 3u) + position * bytes;
                uint word = Words(uvec2(address.x & ~3u, address.y)).w[start >> 2];
                return bytes == 2u ? (word >> ((start & 2u) * 8u)) & 0xFFFFu : word;
            }

            Surface fetch(int instance, int primitive, vec2 barycentrics) {
                Table table = Table(size.zw);
                uvec4 where = table.e[instance * 2];
                uvec4 kind = table.e[instance * 2 + 1];
                bool compact = kind.y != 0u;
                uint stride = compact ? 5u : 8u;
                Words vertices = Words(where.xy);
                vec3 weights = vec3(1.0 - barycentrics.x - barycentrics.y, barycentrics.x, barycentrics.y);
                Surface surface = Surface(vec2(0.0), vec4(0.0), vec2(0.0), vec3(0.0, 1.0, 0.0));
                vec3 corners[3];
                for (int corner = 0; corner < 3; corner++) {
                    uint at = indexAt(where.zw, kind.x, uint(primitive) * 3u + uint(corner)) * stride;
                    vec3 position; vec2 uv; vec4 color; vec2 lightmap;
                    if (compact) {
                        uint first = vertices.w[at], second = vertices.w[at + 1u], packedUv = vertices.w[at + 3u];
                        position = vec3(first & 0xFFFFu, first >> 16, second & 0xFFFFu);
                        lightmap = vec2((second >> 16) & 0xFFu, second >> 24);
                        color = unpackUnorm4x8(vertices.w[at + 2u]);
                        uv = vec2(packedUv & 0xFFFFu, packedUv >> 16) / 32768.0;
                    } else {
                        position = uintBitsToFloat(uvec3(vertices.w[at], vertices.w[at + 1u], vertices.w[at + 2u]));
                        color = unpackUnorm4x8(vertices.w[at + 3u]);
                        uv = uintBitsToFloat(uvec2(vertices.w[at + 4u], vertices.w[at + 5u]));
                        uint packedLight = vertices.w[at + 6u];
                        lightmap = vec2(packedLight & 0xFFFFu, packedLight >> 16);
                    }
                    corners[corner] = position;
                    surface.uv += uv * weights[corner];
                    surface.color += color * weights[corner];
                    surface.lightmap += lightmap * weights[corner];
                }
                vec3 normal = cross(corners[1] - corners[0], corners[2] - corners[0]);
                if (dot(normal, normal) > 0.0) surface.normal = normalize(normal);
                return surface;
            }

            // Confirms a cutout candidate only where the atlas is opaque; opaque geometry never reaches this.
            #define SEARCH(query) while (rayQueryProceedEXT(query)) { \\
                    if (rayQueryGetIntersectionTypeEXT(query, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue; \\
                    Surface candidate = fetch(rayQueryGetIntersectionInstanceCustomIndexEXT(query, false), \\
                            rayQueryGetIntersectionPrimitiveIndexEXT(query, false), rayQueryGetIntersectionBarycentricsEXT(query, false)); \\
                    if (textureLod(atlas, candidate.uv, 0.0).a >= 0.1) rayQueryConfirmIntersectionEXT(query); }

            bool blocked(vec3 origin, vec3 direction, float far) {
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsTerminateOnFirstHitEXT, SOLID_AND_CUTOUT, origin, 0.0, direction, far);
                SEARCH(query)
                return rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
            }

            vec3 positionOf(ivec2 pixel) {
                pixel = clamp(pixel, ivec2(0), ivec2(size.xy) - 1);
                float depth = texelFetch(sceneDepth, pixel, 0).r;
                vec2 uv = (vec2(pixel) + 0.5) / vec2(size.xy);
                vec4 position = inverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                return position.xyz / position.w;
            }

            vec3 normalAt(ivec2 pixel, vec3 center) {
                vec3 left = center - positionOf(pixel - ivec2(1, 0)), right = positionOf(pixel + ivec2(1, 0)) - center;
                vec3 down = center - positionOf(pixel - ivec2(0, 1)), up = positionOf(pixel + ivec2(0, 1)) - center;
                vec3 across = dot(left, left) < dot(right, right) ? left : right;
                vec3 along = dot(down, down) < dot(up, up) ? down : up;
                vec3 normal = normalize(cross(across, along));
                if (dot(normal, center) > 0.0) normal = -normal;
                vec3 axes = abs(normal);
                float largest = max(axes.x, max(axes.y, axes.z));
                if (largest > 0.92) normal = sign(normal) * step(vec3(largest), axes);
                return normal;
            }

            float noiseAt(vec2 pixel) {
                if ((options.x & 8u) != 0u) pixel += 5.588238 * float(options.y & 63u);
                return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
            }

            float occlusion(vec3 origin, vec3 normal, float noise) {
                vec3 side = normalize(abs(normal.y) < 0.9 ? cross(normal, vec3(0.0, 1.0, 0.0)) : cross(normal, vec3(1.0, 0.0, 0.0)));
                vec3 other = cross(normal, side);
                const int RAYS = 8;
                int hits = 0;
                for (int ray = 0; ray < RAYS; ray++) {
                    float turn = fract(noise + float(ray) * 0.618034) * 6.2831853;
                    float spread = (float(ray) + fract(noise * 7.31)) / float(RAYS);
                    float radius = sqrt(spread);
                    vec3 direction = side * cos(turn) * radius + other * sin(turn) * radius + normal * sqrt(1.0 - spread);
                    if (blocked(origin, direction, 1.5)) hits++;
                }
                return 1.0 - 0.35 * float(hits) / float(RAYS);
            }

            float faceShade(vec3 normal) {
                vec3 axes = abs(normal);
                if (axes.y >= axes.x && axes.y >= axes.z) return normal.y > 0.0 ? 1.0 : 0.5;
                return axes.x > axes.z ? 0.6 : 0.8;
            }

            vec3 reflection(vec3 origin, vec3 direction) {
                const float FAR = 96.0;
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, SOLID_AND_CUTOUT, origin, 0.0, direction, FAR);
                SEARCH(query)
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) return sky.rgb;
                Surface surface = fetch(rayQueryGetIntersectionInstanceCustomIndexEXT(query, true),
                        rayQueryGetIntersectionPrimitiveIndexEXT(query, true), rayQueryGetIntersectionBarycentricsEXT(query, true));
                vec3 normal = dot(surface.normal, direction) > 0.0 ? -surface.normal : surface.normal;
                float brightness = max(max(surface.lightmap.x, surface.lightmap.y * sky.w) / 240.0, 0.05);
                vec3 color = textureLod(atlas, surface.uv, 0.0).rgb * surface.color.rgb * faceShade(normal) * brightness;
                float distance = rayQueryGetIntersectionTEXT(query, true);
                return mix(color, sky.rgb, clamp(distance / FAR, 0.0, 1.0));
            }

            // Whether the camera ray first meets an upward facing water surface at the depth's distance.
            bool water(vec3 position, float distance) {
                vec3 direction = position / distance;
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsOpaqueEXT, TRANSLUCENT, vec3(0.0), 0.0, direction, distance + 0.5);
                while (rayQueryProceedEXT(query)) {}
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) return false;
                if (abs(rayQueryGetIntersectionTEXT(query, true) - distance) > 0.3 + distance * 0.004) return false;
                Surface surface = fetch(rayQueryGetIntersectionInstanceCustomIndexEXT(query, true),
                        rayQueryGetIntersectionPrimitiveIndexEXT(query, true), rayQueryGetIntersectionBarycentricsEXT(query, true));
                // Water takes its biome's tint; glass, ice and slime keep a grey vertex colour, darkened only by baked
                // shading, and are not mirrors.
                vec3 tint = surface.color.rgb;
                bool tinted = max(tint.r, max(tint.g, tint.b)) - min(tint.r, min(tint.g, tint.b)) > 0.06;
                return tinted && abs(surface.normal.y) > 0.9 && direction.y < 0.0;
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(uvec2(pixel), size.xy))) return;
                vec4 color = texelFetch(sceneColor, pixel, 0);
                float depth = texelFetch(sceneDepth, pixel, 0).r;
                float reach = uintBitsToFloat(options.z);
                vec3 position = positionOf(pixel);
                float distance = length(position);
                if (depth >= 1.0 || distance > reach || distance < 0.05) {
                    imageStore(lit, pixel, color);
                    return;
                }
                vec3 normal = normalAt(pixel, position);
                float fade = 1.0 - smoothstep(0.75 * reach, reach, distance);
                vec3 origin = position + normal * (0.03 + distance * 0.002);
                float shade = 1.0;
                if ((options.x & 1u) != 0u && light.w > 0.0) {
                    bool facing = dot(normal, light.xyz) > 0.0;
                    if (!facing || blocked(origin, light.xyz, 256.0)) shade = 1.0 - 0.4 * light.w;
                }
                if ((options.x & 2u) != 0u) shade *= occlusion(origin, normal, noiseAt(vec2(pixel)));
                vec3 result = (options.x & 16u) != 0u ? vec3(shade) : color.rgb * mix(1.0, shade, fade);
                if ((options.x & 4u) != 0u && water(position, distance)) {
                    vec3 view = position / distance;
                    vec3 bounce = reflect(view, vec3(0.0, 1.0, 0.0));
                    float fresnel = 0.02 + 0.98 * pow(1.0 - clamp(-view.y, 0.0, 1.0), 5.0);
                    result = mix(result, reflection(position + vec3(0.0, 0.02, 0.0), bounce), fresnel * fade);
                }
                imageStore(lit, pixel, vec4(result, color.a));
            }
            """;

    private BlazeRayShader() {}
}
