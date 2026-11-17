package dev.overhaul.minecraft.blaze;

/**
 * The compute shader that gathers the light coming back off terrain for {@link BlazeShadingShader}, at half size:
 * each of its pixels stands for two by two of the screen and takes a different one of the four each frame. One cosine
 * weighted ray leaves the surface the pixel shows, and what it meets sends back its own colour lit the way the
 * lighting pass lights terrain: by the sun where a shadow ray reaches it, in the colour of the glass that sun passed,
 * by its baked sky and block light, and by its own glow where the block gives light. A miss adds nothing, since the
 * sky's light already arrives through the sky light level. The result blends into a history reprojected from the
 * frame before, so a pixel with a history needs one ray while a newly seen one traces three.
 */
final class BlazeShadingBounceShader {
    private static final String HEAD = """
            #extension GL_EXT_ray_query : require
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_buffer_reference_uvec2 : require
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform accelerationStructureEXT scene;
            // The folded normal and light levels terrain wrote, nothing where no terrain is.
            layout(binding = 1) uniform sampler2D surfaces;
            layout(binding = 2) uniform sampler2D sceneDepth;
            layout(binding = 3) uniform sampler2D atlas;
            layout(binding = 5) uniform sampler2D history;
            // The light that came back, then the distance it was found at, 0 where there is none.
            layout(binding = 6, rgba16f) writeonly uniform image2D accumulated;
            #ifdef COLORED
            layout(binding = 9) uniform sampler3D lightColors;
            layout(binding = 10) uniform sampler2D lightBlocks;
            #endif
            """;
    // Frame's options here: the frame's number, the scene's instances, the reach's bits over the light volume's
    // word, the camera's place.
    private static final String BODY = """
            // Last frame's view projection; motion: where the camera was relative to now, then 1 while a history exists.
            layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Previous { mat4 viewProjection; vec4 motion; };

            // How much of what comes back is shown.
            const float GAIN = 0.6;

            // Interleaved gradient noise that moves every frame, so the history gathers new directions.
            float noiseAt(vec2 pixel, uint salt) {
                pixel += 5.588238 * float((options.x + salt * 17u) & 1023u);
                return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
            }

            vec3 hemisphere(vec3 normal, float turn, float spread) {
                vec3 side = normalize(abs(normal.y) < 0.9 ? cross(normal, vec3(0.0, 1.0, 0.0)) : cross(normal, vec3(1.0, 0.0, 0.0)));
                vec3 other = cross(normal, side);
                return normalize((side * cos(turn) + other * sin(turn)) * sqrt(spread) + normal * sqrt(1.0 - spread));
            }

            // What a ray's hit sends back along it: its own colour under the light the lighting pass would give it,
            // and the glow of a block that gives light.
            vec3 sent(Surface surface, vec3 origin, vec3 direction, float along) {
                vec3 facing = dot(surface.normal, direction) > 0.0 ? -surface.normal : surface.normal;
                vec3 point = origin + direction * along;
                vec2 levels = clamp(surface.lightmap / 240.0, 0.0, 1.0);
                float shadow = 0.0;
                // Without sky light the hit lies in a cave, which may reach below what rays know of.
                if (light.w > 0.0 && levels.y > 0.0 && dot(facing, light.xyz) > 0.0) shadow = sunPassing(point + facing * 0.03, light.xyz, 128.0);
                torchColor = levels.x > 0.0 ? torchAt(point, facing) : vec3(1.0, 0.66, 0.38);
                sunFilter = vec3(1.0);
                vec3 glowing = vec3(0.0);
            #ifdef COLORED
                if (shadow > 0.0) sunFilter = sunThrough(point, facing);
                vec3 away = abs(point);
                if (max(away.x, away.z) < 54.0 && away.y < 22.0) {
                    vec4 block = volumeBlock(point - facing * 0.4);
                    if (block.a > 0.03) glowing = block.rgb * block.a;
                }
            #endif
                vec3 own = linear(textureLod(atlas, surface.uv, 0.0).rgb * surface.color.rgb);
                return own * daylight(facing, levels, shadow, false) + glowing * 1.6;
            }

            vec3 bounce(vec3 origin, vec3 normal, vec2 noise) {
                const float FAR = 28.0;
                vec3 direction = hemisphere(normal, noise.x * 6.2831853, noise.y * 0.98);
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, SOLID_AND_CUTOUT, origin, 0.0, direction, FAR);
                SEARCH(query)
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) return vec3(0.0);
                return min(sent(COMMITTED(query), origin, direction, rayQueryGetIntersectionTEXT(query, true)), vec3(3.0));
            }

            Previous previous() {
                uint low = size.z + options.y * 32u;
                return Previous(uvec2(low, size.w + (low < size.z ? 1u : 0u)));
            }

            // Last frame's light where this surface was then, read through five taps whose distance must match, so
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
                    float w = old.a > 0.0 && abs(old.a - expected) < 0.1 + expected * 0.03 ? (tap == 0 ? 2.0 : 1.0) : 0.0;
                    sum += old.rgb * w;
                    weight += w;
                }
                return weight > 0.0 ? vec4(sum / weight, 1.0) : vec4(0.0);
            }

            void main() {
                ivec2 cell = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(uvec2(cell), (size.xy + 1u) / 2u))) return;
                const ivec2 JITTER[4] = ivec2[](ivec2(0, 0), ivec2(1, 1), ivec2(1, 0), ivec2(0, 1));
                ivec2 pixel = min(cell * 2 + JITTER[options.x & 3u], ivec2(size.xy) - 1);
                vec4 surface = texelFetch(surfaces, pixel, 0);
                vec3 position = positionOf(pixel);
                float distance = length(position), reach = abs(uintBitsToFloat(options.z & 0xFFFFFF00u));
                if (surface.a == 0.0 || distance >= reach || distance < 0.05) {
                    imageStore(accumulated, cell, vec4(0.0));
                    return;
                }
                vec3 normal = unfold(surface.xy * 2.0 - 1.0);
                vec3 origin = position + normal * (0.03 + distance * 0.002);
                vec4 before = recall(position);
                // A pixel with a history averages over frames; a newly seen one has only this frame to go on.
                bool settled = before.a > 0.0;
                int rays = settled ? 1 : 3;
                vec3 gathered = vec3(0.0);
                for (int ray = 0; ray < rays; ray++)
                    gathered += bounce(origin, normal, vec2(noiseAt(vec2(pixel), 1u + uint(ray)), noiseAt(vec2(pixel.yx), 5u + uint(ray))));
                gathered *= GAIN / float(rays) * (1.0 - smoothstep(0.75 * reach, reach, distance));
                if (settled) gathered = mix(before.rgb, gathered, 0.08);
                imageStore(accumulated, cell, vec4(gathered, distance));
            }
            """;

    /** The source, with {@code colored} reading the light volume for the colour of block light, of glass and of lights. */
    static String source(boolean colored) {
        return "#version 460\n" + (colored ? "#define COLORED\n" : "") + HEAD + BlazeShadingShader.FRAME + BlazeRayShader.BUFFERS + BlazeRayShader.SCENE
                + BlazeRayShader.LIGHTS + BlazeShadingShader.SCREEN + BlazeLightVolume.READING + BODY;
    }

    private BlazeShadingBounceShader() {}
}
