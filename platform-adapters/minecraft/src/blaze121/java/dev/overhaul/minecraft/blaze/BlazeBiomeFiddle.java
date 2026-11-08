package dev.overhaul.minecraft.blaze;

import java.util.Arrays;

/**
 * The seeded jitter of the eight quart corners around a quart cell, which a block's biome lookup measures its distance
 * to. A corner's jitter depends only on the zoom seed and the corner, while every block in and around the cell asks
 * for it, so each thread keeps recent cells in a small direct-mapped table.
 */
public final class BlazeBiomeFiddle {
    /** Three jitters, x then y then z, for each of the eight corners. */
    public static final int CELL_DOUBLES = 24;
    private static final int CELLS = 512, MASK = CELLS - 1;
    private static final ThreadLocal<BlazeBiomeFiddle> THREADS = ThreadLocal.withInitial(BlazeBiomeFiddle::new);
    private final double[] jitter = new double[CELLS * CELL_DOUBLES];
    private final int[] xs = new int[CELLS], ys = new int[CELLS], zs = new int[CELLS];
    private final boolean[] filled = new boolean[CELLS];
    private boolean seeded;
    private long seed;

    private BlazeBiomeFiddle() {}

    public static BlazeBiomeFiddle current() {
        return THREADS.get();
    }

    /** The table the starts from {@link #find} index into. */
    public double[] jitter() {
        return jitter;
    }

    /**
     * The start of this cell's jitters when kept, or the bitwise complement of the start when the caller has to write
     * them there now; the slot already names this cell.
     */
    public int find(long seed, int quartX, int quartY, int quartZ) {
        if (!seeded || seed != this.seed) {
            Arrays.fill(filled, false);
            this.seed = seed;
            seeded = true;
        }
        int hash = quartX * 0x9E3779B1 ^ quartY * 0x85EBCA77 ^ quartZ * 0xC2B2AE3D;
        int slot = (hash ^ hash >>> 15) & MASK;
        int start = slot * CELL_DOUBLES;
        if (filled[slot] && xs[slot] == quartX && ys[slot] == quartY && zs[slot] == quartZ) return start;
        xs[slot] = quartX;
        ys[slot] = quartY;
        zs[slot] = quartZ;
        filled[slot] = true;
        return ~start;
    }
}
