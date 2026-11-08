package dev.overhaul.minecraft.blaze;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The biome colours Distant Horizons last looked up, per thread, by the block and biome wrapper instances. Its own
 * cache takes two concurrent map lookups for an interned block and biome pair and a third for the colour, comparing
 * serial strings on the way, for every tinted face of every LOD it builds. A colour kept here is one its cache
 * already held; any change to that cache moves the generation on so nothing kept before is handed out again.
 */
public final class BlazeDhTints {
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final int SLOTS = 1024, MASK = SLOTS - 1;
    private static final ThreadLocal<BlazeDhTints> THREADS = ThreadLocal.withInitial(BlazeDhTints::new);
    private final Object[] blocks = new Object[SLOTS], biomes = new Object[SLOTS];
    private final int[] colors = new int[SLOTS], stamps = new int[SLOTS];
    private int slot;

    private BlazeDhTints() {
        Arrays.fill(stamps, -1);
    }

    public static BlazeDhTints current() {
        return THREADS.get();
    }

    public static int generation() {
        return GENERATION.get();
    }

    /** Called whenever Distant Horizons clears or overwrites its colour cache. */
    public static void invalidate() {
        GENERATION.incrementAndGet();
    }

    /** True when a colour for this pair is kept in this generation; it is then {@link #color()}. */
    public boolean find(Object block, Object biome, int generation) {
        slot = slot(block, biome);
        return stamps[slot] == generation && blocks[slot] == block && biomes[slot] == biome;
    }

    public int color() {
        return colors[slot];
    }

    public void keep(Object block, Object biome, int generation, int color) {
        int at = slot(block, biome);
        blocks[at] = block;
        biomes[at] = biome;
        colors[at] = color;
        stamps[at] = generation;
    }

    private static int slot(Object block, Object biome) {
        int hash = System.identityHashCode(block) * 31 + System.identityHashCode(biome);
        return (hash ^ hash >>> 16) * 0x9E3779B9 >>> 22 & MASK;
    }
}
