package dev.overhaul.minecraft.blaze.shader;

import java.util.Arrays;

/**
 * Vertices one render type received, kept as the calls that set them: position, then whichever of colour, texture,
 * overlay, light and normal the caller gave. {@link BlazeVertexRecorder} fills it and replays it.
 */
final class BlazeVertexLog {
    static final int COLOR = 1, UV = 2, OVERLAY = 4, LIGHT = 8, NORMAL = 16;
    static final int X = 0, Y = 1, Z = 2, RGBA = 3, U = 4, V = 5, OVERLAY_UV = 6, LIGHT_UV = 7, NX = 8, NY = 9, NZ = 10, MASK = 11;
    static final int STRIDE = 12;
    private float[] data = new float[STRIDE * 256];
    private int vertices;

    void clear() {
        vertices = 0;
    }

    int vertices() {
        return vertices;
    }

    /** Opens a vertex at a position and answers its slot. */
    int begin(float x, float y, float z) {
        if ((vertices + 1) * STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
        int slot = vertices++ * STRIDE;
        data[slot + X] = x;
        data[slot + Y] = y;
        data[slot + Z] = z;
        data[slot + MASK] = 0;
        return slot;
    }

    void put(int slot, int field, float value, int flag) {
        if (slot < 0) return;
        data[slot + field] = value;
        flagged(slot, flag);
    }

    void putInt(int slot, int field, int value, int flag) {
        put(slot, field, Float.intBitsToFloat(value), flag);
    }

    void normal(int slot, float x, float y, float z) {
        if (slot < 0) return;
        data[slot + NX] = x;
        data[slot + NY] = y;
        data[slot + NZ] = z;
        flagged(slot, NORMAL);
    }

    boolean has(int slot, int flag) {
        return (Float.floatToRawIntBits(data[slot + MASK]) & flag) != 0;
    }

    float get(int slot, int field) {
        return data[slot + field];
    }

    int getInt(int slot, int field) {
        return Float.floatToRawIntBits(data[slot + field]);
    }

    private void flagged(int slot, int flag) {
        data[slot + MASK] = Float.intBitsToFloat(Float.floatToRawIntBits(data[slot + MASK]) | flag);
    }

    static int pack(int low, int high) {
        return low & 0xFFFF | high << 16;
    }

    static int low(int packed) {
        return (short) packed;
    }

    static int high(int packed) {
        return packed >> 16;
    }

    static int rgba(int r, int g, int b, int a) {
        return (r & 0xFF) << 24 | (g & 0xFF) << 16 | (b & 0xFF) << 8 | a & 0xFF;
    }
}
