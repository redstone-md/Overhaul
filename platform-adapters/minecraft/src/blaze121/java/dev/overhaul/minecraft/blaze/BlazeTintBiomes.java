package dev.overhaul.minecraft.blaze;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The blended biome of recently tinted positions, per thread. A tinted block averages the colour of every position in
 * a square around it, and its neighbours share all but one row of that square, so the same positions are asked again
 * and again while a section is meshed. Any chunk load, unload or biome change on the client moves the generation on,
 * and a kept biome counts only for the generation that was current before it was looked up.
 */
public final class BlazeTintBiomes {
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final int SLOTS = 2048, MASK = SLOTS - 1;
    private static final ThreadLocal<BlazeTintBiomes> THREADS = ThreadLocal.withInitial(BlazeTintBiomes::new);
    private final long[] keys = new long[SLOTS];
    private final int[] stamps = new int[SLOTS];
    private final Object[] biomes = new Object[SLOTS];
    private Object level;

    private BlazeTintBiomes() {}

    public static BlazeTintBiomes current() {
        return THREADS.get();
    }

    /** Called on the client thread whenever a biome some position reads may have changed. */
    public static void invalidate() {
        GENERATION.incrementAndGet();
    }

    /** The generation a lookup starting now belongs to. */
    public static int generation() {
        return GENERATION.get();
    }

    /** The biome kept for this position of this level in this generation, or null. */
    public Object find(Object level, long position, int generation) {
        if (level != this.level) {
            Arrays.fill(biomes, null);
            this.level = level;
            return null;
        }
        int slot = slot(position);
        return keys[slot] == position && stamps[slot] == generation ? biomes[slot] : null;
    }

    public void keep(long position, int generation, Object biome) {
        int slot = slot(position);
        keys[slot] = position;
        stamps[slot] = generation;
        biomes[slot] = biome;
    }

    private static int slot(long position) {
        long hash = position * 0x9E3779B97F4A7C15L;
        return (int) (hash >>> 40) & MASK;
    }
}
