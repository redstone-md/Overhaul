package dev.overhaul.minecraft.blaze;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The clouds of Overhaul's own shading: a layer of soft shapes above the world, walked by the ray of every pixel of
 * sky inside the lighting pass, each step shaded by how much cloud lies between it and the sun, and the ground below
 * darkened where they stand before the sun. Nothing is drawn for them: they take the place of the game's flat clouds.
 */
final class BlazeShadingClouds {
    /** The shapes repeat after this many blocks, so where the camera is among them fits sixteen bits a side. */
    private static final int PERIOD = 2048;
    /** Blocks the layer drifts each tick, the way the game's clouds do. */
    private static final double DRIFT = 0.03;

    /** Whether the world's sky is to carry these clouds: switched on, asked for in the game's options, and a sky that has clouds. */
    static boolean wanted(Minecraft client) {
        return BlazeOptimization.SHADING_CLOUDS.on() && client.level != null && client.options.getCloudsType() != CloudStatus.OFF
                && !Float.isNaN(client.level.effects().getCloudHeight());
    }

    /**
     * What the lighting pass reads of the layer: where the camera is among the shapes, a thirty-second of a block
     * fine, then how far the layer's underside is above the camera; 0 for no clouds.
     */
    static long words(Minecraft client, ClientLevel world, Vec3 position, float partialTick) {
        if (!wanted(client)) return 0;
        double drift = (world.getGameTime() % (long) (PERIOD / DRIFT) + partialTick) * DRIFT;
        long x = (long) (floorMod(position.x + drift) * 32) & 0xFFFF, z = (long) (floorMod(position.z) * 32) & 0xFFFF;
        float above = (float) (world.effects().getCloudHeight() - position.y);
        if (above == 0) above = 1e-3f;
        return (long) Float.floatToRawIntBits(above) << 32 | z << 16 | x;
    }

    private static double floorMod(double value) {
        return value - Math.floor(value / PERIOD) * PERIOD;
    }

    /** The layer in a pixel of sky; needs the frame, the lights and {@code cloudWords}. */
    static final String SOURCE = """
            // Value noise that repeats after a period of cells, so the layer repeats where its place wraps around.
            float cloudHash(vec2 cell, float period) {
                vec2 p = fract(mod(cell, period) * vec2(0.1031, 0.1030));
                p += dot(p, p.yx + 33.33);
                return fract((p.x + p.y) * p.y);
            }

            float cloudNoise(vec2 at, float period) {
                vec2 cell = floor(at), part = fract(at);
                part = part * part * (3.0 - 2.0 * part);
                return mix(mix(cloudHash(cell, period), cloudHash(cell + vec2(1.0, 0.0), period), part.x),
                        mix(cloudHash(cell + vec2(0.0, 1.0), period), cloudHash(cell + vec2(1.0, 1.0), period), part.x), part.y);
            }

            // How thick the cloud is at a place in blocks and a height through the layer, 0 at its underside and 1 at
            // its top: broad masses with smaller shapes on them, flat below and heaped above.
            float cloudAt(vec2 at, float height, bool fine) {
                vec2 unit = at / 2048.0;
                float shape = cloudNoise(unit * 14.0, 14.0) * 0.5 + cloudNoise(unit * 40.0 + 3.7, 40.0) * 0.27;
                shape += fine ? cloudNoise(unit * 112.0 + 9.1, 112.0) * 0.15 + cloudNoise(unit * 320.0, 320.0) * 0.08 : 0.115;
                float edge = 0.52 + 0.16 * height * height + 0.12 * (1.0 - smoothstep(0.0, 0.12, height));
                return clamp((shape - edge) * 7.0, 0.0, 1.0);
            }

            // How much of the sun's light the layer lets down to a place below it: the place is followed toward the sun
            // up into the middle of the layer.
            float cloudShadeAt(vec3 position) {
                uvec2 words = cloudWords();
                float up = uintBitsToFloat(words.y) + 15.0 - position.y;
                if (words.y == 0u || up <= 0.0) return 1.0;
                vec2 at = vec2(words.x & 0xFFFFu, words.x >> 16) / 32.0 + position.xz + light.xz * (up / max(light.y, 0.15));
                return mix(1.0, 0.45, smoothstep(0.0, 0.8, cloudAt(at, 0.5, false)));
            }

            // The layer along a ray from the eye into the sky: its light, already weighed by how much it covers, then
            // how much it covers.
            vec4 cloudsAt(vec3 toward, ivec2 pixel) {
                const float THICK = 30.0;
                const int STEPS = 5;
                uvec2 words = cloudWords();
                float under = uintBitsToFloat(words.y), over = under + THICK;
                // No layer, or the eye inside it.
                if (under == 0.0 || (under < 0.0 && over > 0.0) || abs(toward.y) < 1e-3) return vec4(0.0);
                bool below = under > 0.0;
                float enter = (below ? under : over) / toward.y;
                if (enter <= 0.0 || enter > 5000.0) return vec4(0.0);
                float through = min(THICK / abs(toward.y), 240.0);
                vec2 origin = vec2(words.x & 0xFFFFu, words.x >> 16) / 32.0;
                float turn = fract(52.9829189 * fract(dot(vec2(pixel), vec2(0.06711056, 0.00583715))));
                // Night clouds all but vanish into the dark; a low sun leaves them dim against its glow.
                float low = lowSun();
                vec3 sun = sunColor() * clamp(light.w, 0.0, 1.0) * (0.015 + 0.5 * day()) * (1.0 - 0.45 * low);
                vec3 air = linear(sky.rgb) * (0.1 + 0.22 * day()) + vec3(0.27, 0.33, 0.43) * day() * (1.0 - 0.6 * low) + vec3(0.3, 0.13, 0.05) * low + nightAir() * 0.6;
                vec2 sunward = light.xz / max(light.y, 0.3) * 9.0;
                float left = 1.0;
                vec3 lit = vec3(0.0);
                for (int index = 0; index < STEPS && left > 0.03; index++) {
                    float along = (float(index) + turn) / float(STEPS) * through;
                    float height = clamp(along * abs(toward.y) / THICK, 0.0, 1.0);
                    if (!below) height = 1.0 - height;
                    vec2 at = origin + toward.xz * (enter + along);
                    float thick = cloudAt(at, height, true);
                    if (thick <= 0.0) continue;
                    // What the sun's light passes on its way here, and less of it deep in the cloud's underside.
                    float between = cloudAt(at + sunward, min(height + 0.3, 1.0), false);
                    float shade = max(exp(-2.4 * between), 0.4 * exp(-0.5 * between)) * (0.6 + 0.4 * height);
                    float covers = 1.0 - exp(-thick * through / float(STEPS) * 0.24);
                    lit += left * covers * (air * (0.72 + 0.28 * height) + sun * shade);
                    left *= 1.0 - covers;
                }
                // Far clouds sink into the haze.
                float seen = exp(-enter / 1300.0) * (1.0 - smoothstep(3500.0, 5000.0, enter));
                return vec4(lit, 1.0 - left) * seen;
            }

            """;

    private BlazeShadingClouds() {}
}
