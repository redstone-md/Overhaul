package dev.overhaul.minecraft.blaze;

/**
 * The compute shaders that light the finished world with ray queries, in two passes over one source. The trace pass
 * runs at half resolution: each of its pixels stands for a two by two block of the screen and takes a different one of
 * the four each frame. It rebuilds that pixel's camera-relative position from depth and its normal from the
 * neighbouring depths, and a camera ray finds the terrain the pixel shows and reads its baked light levels, so the
 * terrain can be lit again: a coloured sun through shadow rays that translucent blocks thin rather than stop, a sky
 * tinted ambient, warm block light and, with global illumination, one cosine weighted bounce off what lies around it,
 * each measured against the game's flat lighting. The result is a factor on the pixel's colour; it blends into a
 * history reprojected from the last frame, so a pixel with a history needs only a ray or two of each kind while a
 * newly seen one traces more. The composite pass runs at full resolution: it reads the nearby factors whose distance
 * matches its own, multiplies the colour by them and, where the trace marked water, glass or ice, adds one reflected
 * ray lit the same way as a bounce. Cutout geometry is alpha tested against the atlas while rays search.
 */
final class BlazeRayShader {
    static final int SHADOWS = 1, AMBIENT_OCCLUSION = 2, REFLECTIONS = 4, SHOW_LIGHT = 16, LIGHTING = 32, GLOBAL_ILLUMINATION = 64;
    /** Floats the pass appends after the hit table: last frame's view projection, then the camera's motion and whether a history exists. */
    static final int TRAILER_FLOATS = 20;
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
            layout(binding = 5) uniform sampler2D history;
            // Half resolution: the factor on colour, then the distance it was found at, negative where a reflection waits.
            #ifdef COMPOSITE
            layout(binding = 6, rgba16f) readonly uniform image2D accumulated;
            #else
            layout(binding = 6, rgba16f) writeonly uniform image2D accumulated;
            #endif
            layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Words { uint w[]; };
            layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Table { uvec4 e[]; };
            // Last frame's view projection; motion: where the camera was relative to now, then 1 while a history exists.
            layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Previous { mat4 viewProjection; vec4 motion; };
            // light: direction to the sun or moon, then its strength; sky: the sky colour, then daylight;
            // size: pixels, then the hit table's address; options: flags, frame, traced distance's bits, instances.
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

            #define COMMITTED(query) fetch(rayQueryGetIntersectionInstanceCustomIndexEXT(query, true), \\
                    rayQueryGetIntersectionPrimitiveIndexEXT(query, true), rayQueryGetIntersectionBarycentricsEXT(query, true))

            bool blocked(vec3 origin, vec3 direction, float far) {
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsTerminateOnFirstHitEXT, SOLID_AND_CUTOUT, origin, 0.0, direction, far);
                SEARCH(query)
                return rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
            }

            // How much light passes the translucent layers ahead, water, ice, glass and slime each keeping part of it.
            float transmitted(vec3 origin, vec3 direction, float far) {
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsNoOpaqueEXT, TRANSLUCENT, origin, 0.0, direction, far);
                float light = 1.0;
                while (rayQueryProceedEXT(query)) {
                    if (rayQueryGetIntersectionTypeEXT(query, false) != gl_RayQueryCandidateIntersectionTriangleEXT) continue;
                    Surface candidate = fetch(rayQueryGetIntersectionInstanceCustomIndexEXT(query, false),
                            rayQueryGetIntersectionPrimitiveIndexEXT(query, false), rayQueryGetIntersectionBarycentricsEXT(query, false));
                    light *= 1.0 - 0.5 * textureLod(atlas, candidate.uv, 0.0).a;
                    if (light < 0.1) rayQueryTerminateEXT(query);
                }
                return light;
            }

            // The share of the sun or moon a point sees: rays spread over a small disc around it, so a shadow
            // softens the farther it falls from what casts it.
            float sunlight(vec3 origin, vec3 normal, float noise, int rays) {
                const float SPREAD = 0.035;
                vec3 side = normalize(abs(light.y) < 0.9 ? cross(light.xyz, vec3(0.0, 1.0, 0.0)) : cross(light.xyz, vec3(1.0, 0.0, 0.0)));
                vec3 other = cross(light.xyz, side);
                float seen = 0.0;
                for (int ray = 0; ray < rays; ray++) {
                    float turn = (float(ray) + noise) * (6.2831853 / float(rays));
                    float radius = SPREAD * sqrt(fract(noise * 7.31 + (float(ray) + 0.5) / float(rays)));
                    vec3 direction = normalize(light.xyz + (side * cos(turn) + other * sin(turn)) * radius);
                    if (dot(normal, direction) <= 0.0 || blocked(origin, direction, 256.0)) continue;
                    seen += 1.0;
                }
                seen /= float(rays);
                return seen > 0.0 ? seen * transmitted(origin, light.xyz, 256.0) : 0.0;
            }

            // The baked block and sky light of the terrain a pixel shows, when the camera ray meets terrain at the
            // depth's distance; anything else there, an entity, a hand or a particle, has none to read.
            bool terrainLight(vec3 position, float distance, out vec2 levels) {
                levels = vec2(0.0);
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, SOLID_AND_CUTOUT, vec3(0.0), 0.0, position / distance, distance + 0.5);
                SEARCH(query)
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) return false;
                if (abs(rayQueryGetIntersectionTEXT(query, true) - distance) > 0.3 + distance * 0.004) return false;
                levels = clamp(COMMITTED(query).lightmap / 240.0, 0.0, 1.0);
                return true;
            }

            // The game's own brightness curve for a light level from 0 to 1.
            float brightness(float level) { return level / (4.0 - 3.0 * level); }

            // The light the game itself gives these levels, which the colour on screen already carries.
            float plainLight(vec2 levels) { return max(brightness(levels.x), brightness(levels.y) * sky.w) + 0.03; }

            vec3 skyTint() { return sky.rgb / max(dot(sky.rgb, vec3(0.3, 0.59, 0.11)), 0.05); }

            // The sun warms toward the horizon; the moon is a cool, faint blue.
            vec3 sunColor() {
                if (sky.w < 0.5) return vec3(0.55, 0.65, 1.0) * 0.5;
                return mix(vec3(1.0, 0.55, 0.3), vec3(1.0, 0.96, 0.88), smoothstep(0.0, 0.45, light.y));
            }

            // How much brighter or darker, and in what colour, this light makes a pixel than the game's flat lighting:
            // the sun where rays reach it and the surface faces it, the sky through what occludes it, and blocks
            // in a warm tone.
            vec3 relight(vec2 levels, vec3 origin, vec3 normal, float noise, float occluded, bool shadows, int rays) {
                float open = levels.y * levels.y;
                float direct = 0.0;
                float facing = max(dot(normal, light.xyz), 0.0);
                if (light.w > 0.0 && open > 0.0 && facing > 0.0) direct = light.w * open * facing * (shadows ? sunlight(origin, normal, noise, rays) : 1.0);
                vec3 ambient = mix(vec3(1.0), skyTint(), 0.4) * brightness(levels.y) * sky.w * occluded;
                vec3 block = vec3(1.0, 0.8, 0.55) * brightness(levels.x) * mix(1.0, occluded, 0.5);
                vec3 lit = sunColor() * 0.9 * direct + 0.55 * ambient + block;
                return clamp((lit + 0.03) / plainLight(levels), vec3(0.2), vec3(1.6));
            }

            // What a secondary ray's hit sends back along it: its own colour lit by the sun where a shadow ray
            // reaches it and by its sky and block light levels.
            vec3 hitLight(Surface surface, vec3 origin, vec3 direction, float along) {
                vec3 facing = dot(surface.normal, direction) > 0.0 ? -surface.normal : surface.normal;
                vec3 point = origin + direction * along + facing * 0.03;
                vec2 levels = clamp(surface.lightmap / 240.0, 0.0, 1.0);
                float toSun = max(dot(facing, light.xyz), 0.0);
                float sun = light.w > 0.0 && toSun > 0.0 && levels.y > 0.0 && !blocked(point, light.xyz, 128.0) ? light.w * toSun * levels.y * levels.y : 0.0;
                vec3 incoming = sunColor() * 0.9 * sun + 0.55 * mix(vec3(1.0), skyTint(), 0.4) * brightness(levels.y) * sky.w
                        + vec3(1.0, 0.8, 0.55) * brightness(levels.x);
                return textureLod(atlas, surface.uv, 0.0).rgb * surface.color.rgb * incoming;
            }

            vec3 positionOf(ivec2 pixel) {
                pixel = clamp(pixel, ivec2(0), ivec2(size.xy) - 1);
                float depth = texelFetch(sceneDepth, pixel, 0).r;
                vec2 uv = (vec2(pixel) + 0.5) / vec2(size.xy);
                vec4 position = inverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                return position.xyz / position.w;
            }

            float fadeAt(float distance) {
                float reach = uintBitsToFloat(options.z);
                return 1.0 - smoothstep(0.75 * reach, reach, distance);
            }

            // Whether the pixel shows nothing to light: the sky, or beyond the traced terrain.
            bool unlit(ivec2 pixel, float distance) {
                return texelFetch(sceneDepth, pixel, 0).r >= 1.0 || distance > uintBitsToFloat(options.z) || distance < 0.05;
            }

            // Whether the camera ray first meets water, glass or ice at the depth's distance: 1 for upward facing
            // water, which takes its biome's tint, 2 for the untinted rest, with the surface's normal toward the camera.
            int glazing(vec3 position, float distance, out vec3 normal) {
                normal = vec3(0.0, 1.0, 0.0);
                vec3 direction = position / distance;
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsOpaqueEXT, TRANSLUCENT, vec3(0.0), 0.0, direction, distance + 0.5);
                while (rayQueryProceedEXT(query)) {}
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) return 0;
                if (abs(rayQueryGetIntersectionTEXT(query, true) - distance) > 0.3 + distance * 0.004) return 0;
                Surface surface = COMMITTED(query);
                vec3 tint = surface.color.rgb;
                if (max(tint.r, max(tint.g, tint.b)) - min(tint.r, min(tint.g, tint.b)) > 0.06)
                    return abs(surface.normal.y) > 0.9 && direction.y < 0.0 ? 1 : 0;
                normal = dot(surface.normal, direction) > 0.0 ? -surface.normal : surface.normal;
                return 2;
            }

            #ifdef COMPOSITE
            vec3 reflection(vec3 origin, vec3 direction) {
                const float FAR = 96.0;
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, SOLID_AND_CUTOUT, origin, 0.0, direction, FAR);
                SEARCH(query)
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) return sky.rgb;
                float along = rayQueryGetIntersectionTEXT(query, true);
                vec3 color = hitLight(COMMITTED(query), origin, direction, along) * 0.8;
                return mix(color, sky.rgb, clamp(along / FAR, 0.0, 1.0));
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(uvec2(pixel), size.xy))) return;
                vec4 color = texelFetch(sceneColor, pixel, 0);
                vec3 position = positionOf(pixel);
                float distance = length(position);
                if (unlit(pixel, distance)) {
                    imageStore(lit, pixel, color);
                    return;
                }
                // The nearby half resolution factors, each weighed by how close it lies and how well its distance
                // matches, so light does not bleed across an edge; with no match, the closest in distance.
                ivec2 halfSize = ivec2((size.xy + 1u) / 2u), center = pixel / 2;
                float tolerance = 0.05 + distance * 0.02, weight = 0.0, bestGap = 1e9;
                vec3 sum = vec3(0.0);
                vec4 best = vec4(1.0, 1.0, 1.0, 0.0);
                for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                    ivec2 at = clamp(center + ivec2(x, y), ivec2(0), halfSize - 1);
                    vec4 tap = imageLoad(accumulated, at);
                    if (tap.a == 0.0) continue;
                    float gap = abs(abs(tap.a) - distance) / tolerance;
                    vec2 offset = vec2(at * 2 + 1) - (vec2(pixel) + 0.5);
                    float w = exp(-dot(offset, offset) * 0.25 - gap * gap);
                    sum += tap.rgb * w;
                    weight += w;
                    if (gap < bestGap) { bestGap = gap; best = tap; }
                }
                vec3 factor = weight > 1e-4 ? sum / weight : best.rgb;
                vec3 result = ((options.x & 16u) != 0u ? vec3(1.0 / 1.6) : color.rgb) * factor;
                vec3 normal;
                int kind = best.a < 0.0 ? glazing(position, distance, normal) : 0;
                if (kind != 0) {
                    vec3 view = position / distance;
                    float base = kind == 1 ? 0.02 : 0.04;
                    float fresnel = base + (1.0 - base) * pow(1.0 - clamp(-dot(view, normal), 0.0, 1.0), 5.0);
                    result = mix(result, reflection(position + normal * 0.02, reflect(view, normal)), fresnel * fadeAt(distance));
                }
                imageStore(lit, pixel, vec4(result, color.a));
            }
            #else
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

            // Interleaved gradient noise that moves every frame, so the history gathers new directions.
            float noiseAt(vec2 pixel, uint salt) {
                pixel += 5.588238 * float((options.y + salt * 17u) & 1023u);
                return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
            }

            vec3 hemisphere(vec3 normal, float turn, float spread) {
                vec3 side = normalize(abs(normal.y) < 0.9 ? cross(normal, vec3(0.0, 1.0, 0.0)) : cross(normal, vec3(1.0, 0.0, 0.0)));
                vec3 other = cross(normal, side);
                float radius = sqrt(spread);
                return normalize((side * cos(turn) + other * sin(turn)) * radius + normal * sqrt(1.0 - spread));
            }

            float occlusion(vec3 origin, vec3 normal, float noise, int rays) {
                int hits = 0;
                for (int ray = 0; ray < rays; ray++) {
                    float turn = fract(noise + float(ray) * 0.618034) * 6.2831853;
                    if (blocked(origin, hemisphere(normal, turn, (float(ray) + fract(noise * 7.31)) / float(rays)), 1.5)) hits++;
                }
                return 1.0 - 0.35 * float(hits) / float(rays);
            }

            // One bounce: a cosine weighted ray leaves the surface and what it meets passes on its light. A miss adds
            // nothing, since the sky's own light already arrives through the sky light level.
            vec3 bounce(vec3 origin, vec3 normal, vec2 noise) {
                const float FAR = 24.0;
                vec3 direction = hemisphere(normal, noise.x * 6.2831853, noise.y);
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, SOLID_AND_CUTOUT, origin, 0.0, direction, FAR);
                SEARCH(query)
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) return vec3(0.0);
                return min(hitLight(COMMITTED(query), origin, direction, rayQueryGetIntersectionTEXT(query, true)), vec3(4.0));
            }

            Previous previous() {
                uint low = size.z + options.w * 32u;
                return Previous(uvec2(low, size.w + (low < size.z ? 1u : 0u)));
            }

            // Last frame's factor where this surface was then, read through five taps whose distance must match, so
            // history from another surface is left out; the alpha is 1 when one was found.
            vec4 recall(vec3 position) {
                Previous before = previous();
                vec3 then = position + before.motion.xyz;
                float expected = length(then);
                vec4 clip = before.viewProjection * vec4(then, 1.0);
                if (before.motion.w < 0.5 || clip.w <= 0.0) return vec4(0.0);
                vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
                if (any(lessThanEqual(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) return vec4(0.0);
                const vec2 TAPS[5] = vec2[](vec2(0.0), vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(-1.0, 1.0), vec2(1.0, 1.0));
                vec2 texel = 1.0 / vec2((size.xy + 1u) / 2u);
                vec3 sum = vec3(0.0);
                float weight = 0.0;
                for (int tap = 0; tap < 5; tap++) {
                    vec4 old = textureLod(history, uv + TAPS[tap] * texel * 0.5, 0.0);
                    float w = old.a != 0.0 && abs(abs(old.a) - expected) < 0.1 + expected * 0.03 ? (tap == 0 ? 2.0 : 1.0) : 0.0;
                    sum += old.rgb * w;
                    weight += w;
                }
                return weight > 0.0 ? vec4(sum / weight, 1.0) : vec4(0.0);
            }

            void main() {
                ivec2 cell = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(uvec2(cell), (size.xy + 1u) / 2u))) return;
                const ivec2 JITTER[4] = ivec2[](ivec2(0, 0), ivec2(1, 1), ivec2(1, 0), ivec2(0, 1));
                ivec2 pixel = min(cell * 2 + JITTER[options.y & 3u], ivec2(size.xy) - 1);
                vec3 position = positionOf(pixel);
                float distance = length(position);
                if (unlit(pixel, distance)) {
                    imageStore(accumulated, cell, vec4(1.0, 1.0, 1.0, 0.0));
                    return;
                }
                vec3 normal = normalAt(pixel, position);
                float fade = fadeAt(distance);
                vec3 origin = position + normal * (0.03 + distance * 0.002);
                float noise = noiseAt(vec2(pixel), 0u);
                vec4 before = recall(position);
                // A pixel with a history averages over frames; a newly seen one has only this frame to go on.
                bool settled = before.a > 0.0;
                int rays = settled ? 1 : 4;
                bool shadows = (options.x & 1u) != 0u;
                float occluded = (options.x & 2u) != 0u ? occlusion(origin, normal, noise, rays * 2) : 1.0;
                vec2 levels;
                vec3 shade;
                bool terrain = (options.x & 96u) != 0u && terrainLight(position, distance, levels);
                if ((options.x & 32u) != 0u && terrain) shade = relight(levels, origin, normal, noise, occluded, shadows, rays);
                else shade = vec3(occluded * (shadows && light.w > 0.0 ? 1.0 - 0.6 * light.w * (1.0 - sunlight(origin, normal, noise, rays)) : 1.0));
                vec3 factor = mix(vec3(1.0), shade, fade);
                if ((options.x & 64u) != 0u && terrain) {
                    int bounces = settled ? 1 : 2;
                    vec3 gathered = vec3(0.0);
                    for (int ray = 0; ray < bounces; ray++)
                        gathered += bounce(origin, normal, vec2(noiseAt(vec2(pixel), 1u + uint(ray)), noiseAt(vec2(pixel.yx), 3u + uint(ray))));
                    factor += gathered / float(bounces) * (0.6 * fade / plainLight(levels));
                }
                factor = min(factor, vec3(3.0));
                if (settled) factor = mix(before.rgb, factor, 0.1);
                vec3 facing;
                bool glazed = (options.x & 4u) != 0u && glazing(position, distance, facing) != 0;
                imageStore(accumulated, cell, vec4(factor, glazed ? -distance : distance));
            }
            #endif
            """;

    private BlazeRayShader() {}
}
