package dev.overhaul.minecraft.blaze;

/**
 * The biomes a thread last computed from a level's climate noise, by quart position. World generation asks for the
 * noise biome of every quart it cannot read from a loaded chunk, and surface rules and biome blending ask for the
 * same quarts over and over across a chunk's edge, each time sampling half a dozen noise fields from scratch. A
 * level's noise biome at a quart never changes, so one kept here is the one its biome source would return again.
 */
public final class BlazeNoiseBiomes {
    private static final int BITS = 11, SLOTS = 1 << BITS;
    private static final ThreadLocal<BlazeNoiseBiomes> THREADS = ThreadLocal.withInitial(BlazeNoiseBiomes::new);
    private final Object[] levels = new Object[SLOTS], biomes = new Object[SLOTS];
    private final long[] keys = new long[SLOTS];
    private int slot;

    private BlazeNoiseBiomes() {
    }

    public static BlazeNoiseBiomes current() {
        return THREADS.get();
    }

    /** True when this thread kept the biome of that quart in that level; it is then {@link #biome()}. */
    public boolean find(Object level, int x, int y, int z) {
        long key = key(x, y, z);
        slot = slot(key);
        return levels[slot] == level && keys[slot] == key;
    }

    public Object biome() {
        return biomes[slot];
    }

    public void keep(Object level, int x, int y, int z, Object biome) {
        long key = key(x, y, z);
        int at = slot(key);
        levels[at] = level;
        keys[at] = key;
        biomes[at] = biome;
    }

    /** 24 bits per horizontal quart cover the whole world border; 16 cover any build height. */
    private static long key(int x, int y, int z) {
        return (x & 0xFFFFFFL) << 40 | (z & 0xFFFFFFL) << 16 | y & 0xFFFFL;
    }

    private static int slot(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 64 - BITS);
    }
}
