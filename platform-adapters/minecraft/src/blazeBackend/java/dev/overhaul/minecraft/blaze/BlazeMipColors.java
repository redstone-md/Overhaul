package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Mip levels averaged in linear light and weighted by alpha, as Sodium and Embeddium build them. The game averages
 * sRGB values and lets fully transparent pixels pull colour in, so distant blocks come out darker and speckled,
 * which shimmers as the camera moves. Alpha is the plain average, so a level keeps the share of the sprite its parents
 * cover: Sodium quarters it beside a transparent pixel, which thins far leaves down to bare trunks. Colours are
 * ABGR: red in the low byte, alpha in the high one.
 */
public final class BlazeMipColors {
    private static final float[] TO_LINEAR = new float[256];
    private static final int LINEAR_STEPS = 4096;
    private static final byte[] TO_SRGB = new byte[LINEAR_STEPS + 1];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i <= LINEAR_STEPS; i++) {
            double c = (double) i / LINEAR_STEPS;
            double s = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
            TO_SRGB[i] = (byte) Math.round(Math.min(1.0, Math.max(0.0, s)) * 255.0);
        }
    }

    private BlazeMipColors() {
    }

    /** One pixel of the next mip level from its four parents. */
    public static int blend(int one, int two, int three, int four) {
        return pair(pair(one, two), pair(three, four));
    }

    private static int pair(int one, int two) {
        int alphaOne = one >>> 24, alphaTwo = two >>> 24;
        if (alphaOne == alphaTwo) return pack(0.5F, one, 0.5F, two, alphaOne);
        if (alphaOne == 0) return two & 0xFFFFFF | (alphaTwo >> 1) << 24;
        if (alphaTwo == 0) return one & 0xFFFFFF | (alphaOne >> 1) << 24;
        float scale = 1.0F / (alphaOne + alphaTwo);
        return pack(alphaOne * scale, one, alphaTwo * scale, two, (alphaOne + alphaTwo) >> 1);
    }

    private static int pack(float weightOne, int one, float weightTwo, int two, int alpha) {
        int color = alpha << 24;
        for (int shift = 0; shift < 24; shift += 8) {
            float linear = TO_LINEAR[one >>> shift & 0xFF] * weightOne + TO_LINEAR[two >>> shift & 0xFF] * weightTwo;
            color |= toSrgb(linear) << shift;
        }
        return color;
    }

    private static int toSrgb(float linear) {
        int index = Math.round(linear * LINEAR_STEPS);
        return TO_SRGB[Math.max(0, Math.min(LINEAR_STEPS, index))] & 0xFF;
    }

    /**
     * Gives every fully transparent pixel the sprite's alpha-weighted average colour, still transparent, so mip levels
     * that blend it with opaque neighbours do not darken toward the black the file stored there.
     */
    public static void fillTransparent(NativeImage image) {
        if (image.format() != NativeImage.Format.RGBA) return;
        int width = image.getWidth(), height = image.getHeight();
        float r = 0, g = 0, b = 0, total = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = image.getPixelRGBA(x, y);
                int alpha = color >>> 24;
                if (alpha == 0) continue;
                r += TO_LINEAR[color & 0xFF] * alpha;
                g += TO_LINEAR[color >>> 8 & 0xFF] * alpha;
                b += TO_LINEAR[color >>> 16 & 0xFF] * alpha;
                total += alpha;
            }
        }
        if (total == 0) return;
        int average = toSrgb(r / total) | toSrgb(g / total) << 8 | toSrgb(b / total) << 16;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (image.getPixelRGBA(x, y) >>> 24 == 0) image.setPixelRGBA(x, y, average);
            }
        }
    }
}
