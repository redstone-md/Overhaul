package dev.overhaul.minecraft.blaze;

/**
 * The compute shader that gathers the light coming back off terrain for {@link BlazeShadingShader}, at half size:
 * each of its pixels stands for two by two of the screen and takes a different one of the four each frame. One cosine
 * weighted ray leaves the surface the pixel shows, and what it meets sends back its own colour lit the way the
 * lighting pass lights terrain: by the sun where a shadow ray reaches it, in the colour of the glass that sun passed,
 * by its baked sky and block light, and by its own glow where the block gives light. A miss adds nothing, since the
 * sky's light already arrives through the sky light level. The result blends into a history reprojected from the
 * frame before, so a pixel with a history needs one ray while a newly seen one traces three.
 * {@link BlazeShadingPathShader} gathers in its place with the same pixels and history.
 */
final class BlazeShadingBounceShader {
    private static final String RAYS = """
            #extension GL_EXT_ray_query : require
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_buffer_reference_uvec2 : require
            #define TRACED
            layout(binding = 0) uniform accelerationStructureEXT scene;
            layout(binding = 3) uniform sampler2D atlas;
            """;
    private static final String HEAD = """
            layout(local_size_x = 8, local_size_y = 8) in;
            // The folded normal and light levels terrain wrote, nothing where no terrain is.
            layout(binding = 1) uniform sampler2D surfaces;
            layout(binding = 2) uniform sampler2D sceneDepth;
            layout(binding = 5) uniform sampler2D history;
            // The light that came back, then the distance it was found at, 0 where there is none.
            layout(binding = 6, rgba16f) writeonly uniform image2D accumulated;
            #ifdef COLORED
            layout(binding = 9) uniform sampler3D lightColors;
            layout(binding = 10) uniform sampler2D lightBlocks;
            #endif
            #ifndef TRACED
            // Without a scene's hit table: last frame's view projection in four texels, then the motion.
            layout(binding = 12) uniform sampler2D formerFrame;
            #endif
            """;
    // Frame's options here: the frame's number, the scene's instances, the reach's bits over the light volume's
    // word, the camera's place.
    private static final String HISTORY = """
            // Last frame's view projection; motion: where the camera was relative to now, then 1 while a history exists.
            struct Former { mat4 viewProjection; vec4 motion; };
            #ifdef TRACED
            layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer Previous { mat4 viewProjection; vec4 motion; };
            #endif

            // Interleaved gradient noise that moves every frame, so the history gathers new directions.
            float noiseAt(vec2 pixel, uint salt) {
                pixel += 5.588238 * float((options.x + salt * 17u) & 1023u);
                return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
            }

            // Two numbers to aim a ray by: each cell of the screen starts somewhere of its own and steps evenly from
            // frame to frame, so the frames it averages over cover every way once and no weave shows across cells.
            vec2 aimAt(ivec2 pixel, uint salt) {
                uvec2 at = uvec2(pixel >> 1);
                uint mixed = at.x * 0x9E3779B1u ^ at.y * 0x85EBCA77u ^ salt * 0xC2B2AE3Du;
                mixed ^= mixed >> 15u; mixed *= 0x2C1B3C6Du; mixed ^= mixed >> 12u; mixed *= 0x297A2D39u; mixed ^= mixed >> 15u;
                vec2 start = vec2(mixed & 0xFFFFu, mixed >> 16u) / 65536.0;
                return fract(start + float(options.x & 1023u) * vec2(0.7548776662, 0.5698402910));
            }

            vec3 hemisphere(vec3 normal, float turn, float spread) {
                vec3 side = normalize(abs(normal.y) < 0.9 ? cross(normal, vec3(0.0, 1.0, 0.0)) : cross(normal, vec3(1.0, 0.0, 0.0)));
                vec3 other = cross(normal, side);
                return normalize((side * cos(turn) + other * sin(turn)) * sqrt(spread) + normal * sqrt(1.0 - spread));
            }

            Former former() {
            #ifdef TRACED
                uint low = size.z + options.y * 32u;
                Previous before = Previous(uvec2(low, size.w + (low < size.z ? 1u : 0u)));
                return Former(before.viewProjection, before.motion);
            #else
                return Former(mat4(texelFetch(formerFrame, ivec2(0, 0), 0), texelFetch(formerFrame, ivec2(1, 0), 0), texelFetch(formerFrame, ivec2(2, 0), 0),
                        texelFetch(formerFrame, ivec2(3, 0), 0)), texelFetch(formerFrame, ivec2(4, 0), 0));
            #endif
            }

            // Last frame's light where this surface was then, read through five taps whose distance must match, so
            // history from another surface is left out; the taps lie a pixel apart, so the light also spreads a little
            // each frame and single rays even out. The alpha is 1 when one was found.
            vec4 recall(vec3 position) {
                Former before = former();
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
                    vec4 old = textureLod(history, uv + TAPS[tap] * texel, 0.0);
                    float w = old.a > 0.0 && abs(old.a - expected) < 0.1 + expected * 0.03 ? (tap == 0 ? 2.0 : 1.0) : 0.0;
                    sum += old.rgb * w;
                    weight += w;
                }
                return weight > 0.0 ? vec4(sum / weight, 1.0) : vec4(0.0);
            }

            """;
    /** What a ray's hit sends back; needs the scene and the lights. */
    static final String SENT = """
            // What a ray's hit sends back along it: its own colour under the light the lighting pass would give it,
            // and the glow of a block that gives light. Its colour and the side that was met are given beside.
            vec3 sent(Surface surface, vec3 origin, vec3 direction, float along, out vec3 own, out vec3 facing) {
                facing = dot(surface.normal, direction) > 0.0 ? -surface.normal : surface.normal;
                vec3 point = origin + direction * along;
                vec2 levels = clamp(surface.lightmap / 240.0, 0.0, 1.0);
                float shadow = 0.0;
                // Without sky light the hit lies in a cave, which may reach below what rays know of.
                if (light.w > 0.0 && levels.y > 0.0 && dot(facing, light.xyz) > 0.0) shadow = sunPassing(point + facing * 0.03, light.xyz, 128.0);
                torchColor = levels.x > 0.0 ? torchAt(point, facing) : vec3(1.0, 0.66, 0.38);
                sunFilter = vec3(1.0);
                vec3 glowing = vec3(0.0);
            #ifdef COLORED
                if (shadow > 0.0) sunFilter = sunThrough(point, facing, light.xyz);
                vec3 away = abs(point);
                if (max(away.x, away.z) < 54.0 && away.y < 22.0) {
                    vec4 block = volumeBlock(point - facing * 0.4);
                    if (block.a > 0.03) glowing = block.rgb * block.a;
                }
            #endif
                own = linear(textureLod(atlas, surface.uv, 0.0).rgb * surface.color.rgb);
                return own * daylight(facing, levels, shadow, false) + glowing * 1.6;
            }

            """;
    private static final String BOUNCE = """
            // How much of what comes back is shown.
            const float GAIN = 0.6;

            // The light one ray finds coming back to a surface; a miss adds nothing.
            vec3 gather(vec3 origin, vec3 normal, ivec2 pixel, int ray, float skyLevel) {
                const float FAR = 28.0;
                vec3 direction = hemisphere(normal, aimAt(pixel, uint(ray)).x * 6.2831853, aimAt(pixel, uint(ray)).y * 0.98);
                rayQueryEXT query;
                rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, SOLID_AND_CUTOUT, origin, 0.0, direction, FAR);
                SEARCH(query)
                if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) return vec3(0.0);
                vec3 own, facing;
                return GAIN * min(sent(COMMITTED(query), origin, direction, rayQueryGetIntersectionTEXT(query, true), own, facing), vec3(3.0));
            }

            """;
    /** The pixels and their history around a {@code gather} that finds one ray's or one path's light. */
    static final String MAIN = """
            void main() {
                ivec2 cell = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(uvec2(cell), (size.xy + 1u) / 2u))) return;
                const ivec2 JITTER[4] = ivec2[](ivec2(0, 0), ivec2(1, 1), ivec2(1, 0), ivec2(0, 1));
                ivec2 pixel = min(cell * 2 + JITTER[options.x & 3u], ivec2(size.xy) - 1);
                vec4 surface = texelFetch(surfaces, pixel, 0);
                vec3 position = positionOf(pixel);
                float distance = length(position), share = pathShare(position);
                if (surface.a == 0.0 || share <= 0.0 || distance < 0.05) {
                    imageStore(accumulated, cell, vec4(0.0));
                    return;
                }
                vec3 normal = unfold(surface.xy * 2.0 - 1.0);
                vec3 origin = position + normal * (0.03 + distance * 0.002);
                vec4 before = recall(position);
                // A pixel with a history averages over frames; a newly seen one has only this frame to go on.
                bool settled = before.a > 0.0;
            #ifdef PATHS
                // A path finds its light less often than one bounce does, so more of them are sent.
                int rays = settled ? 2 : 4;
            #else
                int rays = settled ? 1 : 3;
            #endif
                vec3 gathered = vec3(0.0);
                float skyLevel = clamp((surface.a * 255.0 - 1.0) / 254.0, 0.0, 1.0);
                for (int ray = 0; ray < rays; ray++) gathered += gather(origin, normal, pixel, ray, skyLevel);
                gathered *= share / float(rays);
                // One ray that met something far brighter than the rest must not flare up, so it counts for less.
                if (settled) gathered = mix(before.rgb, min(gathered, before.rgb * 3.0 + 0.2), 0.04);
                imageStore(accumulated, cell, vec4(gathered, distance));
            }
            """;

    /** The source, with {@code colored} reading the light volume for the colour of block light, of glass and of lights. */
    static String source(boolean colored) {
        return head(colored, true) + SENT + BOUNCE + MAIN;
    }

    /** All a gathering shader has before its own part: the frame, the lights, the screen, the light volume, the history; with {@code traced} the scene too. */
    static String head(boolean colored, boolean traced) {
        return "#version 460\n" + (colored ? "#define COLORED\n" : "") + (traced ? RAYS : "") + HEAD + BlazeShadingShader.FRAME
                + (traced ? BlazeRayShader.BUFFERS + BlazeRayShader.SCENE : "") + BlazeRayShader.LIGHTS + BlazeShadingShader.SCREEN + BlazeLightVolume.READING
                + BlazeShadingPathShader.WITHIN + HISTORY;
    }

    private BlazeShadingBounceShader() {}
}
