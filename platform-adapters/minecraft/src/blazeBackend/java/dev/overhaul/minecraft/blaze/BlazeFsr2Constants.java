package dev.overhaul.minecraft.blaze;

import java.nio.ByteBuffer;

/**
 * The per-frame FSR2 constant blocks, packed in std140 as the SDK's shaders declare them, and the Halton jitter
 * sequence the projection is offset by. Depth reaches FSR in the SDK's non-inverted [0, 1] convention, which the
 * motion pass converts the game's depth into.
 */
final class BlazeFsr2Constants {
    static final int FSR2_OFFSET = 0, SPD_OFFSET = 256, RCAS_OFFSET = 512, SLOT_BYTES = 1024;
    private static final int BASE_PHASES = 8;

    private final int renderWidth, renderHeight, displayWidth, displayHeight;
    private int frameIndex = -1, jitterIndex;
    private float jitterPhases, preExposure = 1;
    private float jitterX, jitterY;

    BlazeFsr2Constants(int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
    }

    /** The SDK's phase count: eight samples per render pixel's worth of display pixels. */
    static int phaseCount(int renderWidth, int displayWidth) {
        float ratio = (float) displayWidth / renderWidth;
        return Math.max(1, (int) (BASE_PHASES * ratio * ratio));
    }

    /** Advances to the next jitter, in render pixels within [-0.5, 0.5]. */
    void advanceJitter() {
        int phases = phaseCount(renderWidth, displayWidth);
        int index = (jitterIndex++ % phases) + 1;
        jitterX = halton(index, 2) - 0.5f;
        jitterY = halton(index, 3) - 0.5f;
    }

    float jitterX() { return jitterX; }
    float jitterY() { return jitterY; }

    /** The jitter as a clip space offset; row 0 of the game's images is NDC y -1, so both axes keep their sign. */
    float clipJitterX() { return 2 * jitterX / renderWidth; }
    float clipJitterY() { return 2 * jitterY / renderHeight; }

    private static float halton(int index, int base) {
        float fraction = 1, result = 0;
        while (index > 0) {
            fraction /= base;
            result += fraction * (index % base);
            index /= base;
        }
        return result;
    }

    /**
     * Packs cbFSR2 for one dispatch.
     *
     * @param verticalFov the camera's vertical field of view in radians
     * @param near the near plane the motion pass converted depth with
     * @param far the far plane the motion pass converted depth with
     * @param seconds the frame's duration
     * @param reset whether history is dropped this frame
     */
    void packFsr2(ByteBuffer out, int at, float verticalFov, float near, float far, float seconds, boolean reset) {
        int phases = phaseCount(renderWidth, displayWidth);
        if (reset || jitterPhases == 0) jitterPhases = phases;
        else if (phases > jitterPhases) jitterPhases++;
        else if (phases < jitterPhases) jitterPhases--;
        frameIndex = reset ? 0 : frameIndex + 1;
        float previousPreExposure = preExposure;
        preExposure = 1;
        float aspect = (float) renderWidth / renderHeight;
        float horizontal = (float) (2 * Math.atan(Math.tan(verticalFov / 2) * aspect));
        float cotHalfVertical = (float) (1 / Math.tan(verticalFov / 2));
        float q = far / (near - far);
        int lumaWidth = renderWidth / (2 << BlazeFsr2Resources.SHADING_CHANGE_MIP), lumaHeight = renderHeight / (2 << BlazeFsr2Resources.SHADING_CHANGE_MIP);
        ByteBuffer block = out.duplicate().order(out.order()).position(at);
        block.putInt(renderWidth).putInt(renderHeight);
        block.putInt(renderWidth).putInt(renderHeight);
        block.putInt(displayWidth).putInt(displayHeight);
        block.putInt(renderWidth).putInt(renderHeight);
        block.putInt(lumaWidth).putInt(lumaHeight);
        block.putInt(BlazeFsr2Resources.SHADING_CHANGE_MIP).putInt(frameIndex);
        block.putFloat(-q).putFloat(q * near).putFloat(aspect / cotHalfVertical).putFloat(1 / cotHalfVertical);
        block.putFloat(jitterX).putFloat(jitterY);
        // Motion vectors arrive in render UV units, so the scale is 1 / render size times the render size.
        block.putFloat(1).putFloat(1);
        block.putFloat((float) renderWidth / displayWidth).putFloat((float) renderHeight / displayHeight);
        block.putFloat(0).putFloat(0);
        block.putFloat(preExposure).putFloat(previousPreExposure);
        block.putFloat((float) Math.tan(horizontal / 2));
        block.putFloat(jitterPhases);
        block.putFloat(Math.max(0, Math.min(1, seconds)));
        block.putFloat(0);
        block.putFloat(1);
    }

    /** The luminance pyramid's thread groups, as the SDK's SpdSetup counts them over the render rectangle. */
    int[] spdGroups() {
        return new int[]{(renderWidth + 63) / 64, (renderHeight + 63) / 64};
    }

    void packSpd(ByteBuffer out, int at) {
        int[] groups = spdGroups();
        int mips = Math.min((int) Math.floor(Math.log(Math.max(renderWidth, renderHeight)) / Math.log(2)), 12);
        ByteBuffer block = out.duplicate().order(out.order()).position(at);
        block.putInt(mips).putInt(groups[0] * groups[1]).putInt(0).putInt(0).putInt(renderWidth).putInt(renderHeight);
    }

    /** @param sharpness 0 for none to 1 for the most, as the SDK's dispatch description takes it */
    static void packRcas(ByteBuffer out, int at, float sharpness) {
        float stops = -2 * Math.max(0, Math.min(1, sharpness)) + 2;
        float scale = (float) Math.pow(2, -stops);
        int half = half(scale);
        ByteBuffer block = out.duplicate().order(out.order()).position(at);
        block.putInt(Float.floatToRawIntBits(scale)).putInt(half | half << 16).putInt(0).putInt(0);
    }
    /** IEEE half of a float, rounded to nearest even; Java 17 has no Float.floatToFloat16. Tiny values flush to zero. */
    private static int half(float value) {
        int bits = Float.floatToRawIntBits(value), sign = bits >>> 16 & 0x8000, exponent = (bits >>> 23 & 0xFF) - 112, mantissa = bits & 0x7FFFFF;
        if (exponent <= 0) return sign;
        if (exponent >= 31) return sign | 0x7C00;
        int half = exponent << 10 | mantissa >>> 13, rest = mantissa & 0x1FFF;
        if (rest > 0x1000 || rest == 0x1000 && (half & 1) != 0) half++;
        return sign | Math.min(half, 0x7C00);
    }
}
