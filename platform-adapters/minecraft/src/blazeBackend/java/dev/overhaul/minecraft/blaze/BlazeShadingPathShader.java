package dev.overhaul.minecraft.blaze;

/**
 * The compute shader that traces paths of light for {@link BlazeShadingShader}, in the place of
 * {@link BlazeShadingBounceShader} and with its history: a path leaves the surface a pixel shows and turns at each
 * thing it meets, which sends back its own colour under the sun, where a line toward the sun is free, and under the
 * block light there. A path that ends in the open brings the sky's light, so the lighting pass no longer takes that
 * from the sky light level: a room is lit through its windows, and what stands in the way shades it. The traced
 * variant sends ray queries into the scene of triangles. The walked one needs no such card: it walks the blocks the
 * {@link BlazeLightVolume} keeps around the camera, a whole block at a time, where a solid block carries its colour.
 */
final class BlazeShadingPathShader {
    /** How much of a place's light paths find; needs the frame, and {@code TRACED} defined where rays are sent. */
    static final String WITHIN = """
            // The share of a place's light that is looked for: all of it near the camera, none where what is known ends.
            float pathShare(vec3 position) {
            #ifdef TRACED
                float reach = abs(uintBitsToFloat(options.z & 0xFFFFFF00u));
                return 1.0 - smoothstep(0.75 * reach, reach, length(position));
            #else
                vec3 away = abs(position);
                return (1.0 - smoothstep(34.0, 44.0, max(away.x, away.z))) * (1.0 - smoothstep(10.0, 16.0, away.y));
            #endif
            }

            """;
    private static final String SKY = """
            // How much of its light a thing sends on along a path.
            const float BACK = 0.8;

            // The sky's light from one way, such that open ground takes from all ways what its sky light level gave it.
            vec3 skyFrom(vec3 direction) { return fromSky(vec3(0.0, 1.0, 0.0), 1.0) * (0.4 + 0.9 * max(direction.y, 0.0)); }

            // How surely a way that meets nothing ends at the sky: what is known ends somewhere, also in a cave.
            float openTo(float skyLevel) { return smoothstep(0.0, 0.25, skyLevel); }

            """;
    private static final String TRACED = """
            vec3 gather(vec3 origin, vec3 normal, ivec2 pixel, int ray, float skyLevel) {
                const int TURNS = 3;
                vec3 gathered = vec3(0.0), carried = vec3(1.0);
                for (int turn = 0; turn < TURNS; turn++) {
                    uint salt = uint(ray + turn * 3);
                    vec2 aim = aimAt(pixel, salt);
                    vec3 direction = hemisphere(normal, aim.x * 6.2831853, aim.y * 0.98);
                    rayQueryEXT query;
                    rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, SOLID_AND_CUTOUT, origin, 0.0, direction, turn == 0 ? 64.0 : 32.0);
                    SEARCH(query)
                    if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionNoneEXT) {
                        gathered += carried * skyFrom(direction) * openTo(skyLevel);
                        break;
                    }
                    float along = rayQueryGetIntersectionTEXT(query, true);
                    Surface surface = COMMITTED(query);
                    // Where a path ends the sky light level stands in for the sky; before that the next ray looks for it.
                    skyKept = turn + 1 == TURNS ? 1.0 : 0.0;
                    vec3 own, facing;
                    gathered += carried * min(sent(surface, origin, direction, along, own, facing), vec3(3.0));
                    carried *= own * BACK;
                    skyLevel = clamp(surface.lightmap.y / 240.0, 0.0, 1.0);
                    origin += direction * along + facing * 0.03;
                    normal = facing;
                }
                return gathered;
            }

            """;
    private static final String WALKED = """
            // What a walk through the blocks came to: whether it met a block, that block's word, the side it was met
            // on, how far along, and what the glass, leaves and water on the way left of the light.
            struct Walked { bool met; vec4 block; vec3 normal; float along; vec3 passed; };

            // Walks block by block from a place along a line, the block it starts in left out, until a solid block
            // stands in the way, or with glowing a block that gives light, or the blocks known end.
            Walked walk(vec3 origin, vec3 direction, int steps, bool glowing) {
                vec3 way = direction + vec3(equal(direction, vec3(0.0))) * 1e-4;
                vec3 from = origin + volumeCamera();
                vec3 cell = floor(from), turn = sign(way);
                vec3 next = (cell + max(turn, 0.0) - from) / way, each = abs(1.0 / way);
                Walked walked = Walked(false, vec4(0.0), vec3(0.0), 0.0, vec3(1.0));
                for (int i = 0; i < steps; i++) {
                    vec3 first = vec3(lessThanEqual(next, min(next.yzx, next.zxy)));
                    if (first.x + first.y + first.z > 1.0) first = first.x > 0.0 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, first.y, 1.0 - first.y);
                    walked.along = dot(first, next);
                    next += first * each;
                    cell += first * turn;
                    vec3 at = cell + 0.5 - volumeCamera();
                    vec3 away = abs(at);
                    if (max(away.x, away.z) > 54.0 || away.y > 22.0) break;
                    vec4 block = volumeWord(at);
                    bool gives = block.a > 0.03;
                    if (solid(block) || gives && glowing) {
                        walked.met = true;
                        walked.block = block;
                        walked.normal = -first * turn;
                        return walked;
                    }
                    if (!gives) walked.passed *= block.rgb;
                }
                return walked;
            }

            vec3 gather(vec3 origin, vec3 normal, ivec2 pixel, int ray, float skyLevel) {
                const int TURNS = 2;
                vec3 gathered = vec3(0.0), carried = vec3(1.0);
                float open = openTo(skyLevel);
                // The blocks know nothing of sky light levels, so the sky's light comes by the paths' ends alone.
                skyKept = 0.0;
                for (int turn = 0; turn < TURNS; turn++) {
                    uint salt = uint(ray + turn * 3);
                    vec2 aim = aimAt(pixel, salt);
                    vec3 direction = hemisphere(normal, aim.x * 6.2831853, aim.y * 0.98);
                    Walked walked = walk(origin, direction, 56, true);
                    carried *= walked.passed;
                    if (!walked.met) {
                        gathered += carried * skyFrom(direction) * open;
                        break;
                    }
                    // A block that gives light ends the path with its glow.
                    if (!solid(walked.block)) {
                        gathered += carried * min(walked.block.rgb * walked.block.a * 1.6, vec3(3.0));
                        break;
                    }
                    vec3 facing = walked.normal, point = origin + direction * walked.along + facing * 0.02;
                    float shadow = 0.0;
                    sunFilter = vec3(1.0);
                    // Under no sky the sun is not looked for: what is known of a cave may end below its roof.
                    if (light.w > 0.0 && open > 0.0 && dot(facing, light.xyz) > 0.0) {
                        Walked sunward = walk(point, light.xyz, 40, false);
                        shadow = sunward.met ? 0.0 : 1.0;
                        sunFilter = sunward.passed;
                    }
                    vec3 lamps = textureLod(lightColors, (point + facing * 0.5 + volumeCamera()) / vec3(128.0, 64.0, 128.0), 0.0).rgb;
                    float level = min(max(lamps.r, max(lamps.g, lamps.b)), 1.0);
                    torchColor = level > 0.0 ? torchAt(point, facing) : vec3(1.0, 0.66, 0.38);
                    vec3 own = linear(walked.block.rgb);
                    gathered += carried * min(own * daylight(facing, vec2(level, 0.0), shadow, false), vec3(3.0));
                    carried *= own * BACK;
                    origin = point;
                    normal = facing;
                }
                return gathered;
            }

            """;

    /**
     * The source: with {@code traced} for a card with ray queries, else walking the blocks, which needs the light
     * volume and so is always {@code colored}; a traced one reads the volume only with {@code colored}.
     */
    static String source(boolean colored, boolean traced) {
        return BlazeShadingBounceShader.head(colored || !traced, traced) + "#define PATHS\n" + SKY + (traced ? BlazeShadingBounceShader.SENT + TRACED : WALKED) + BlazeShadingBounceShader.MAIN;
    }

    private BlazeShadingPathShader() {}
}
