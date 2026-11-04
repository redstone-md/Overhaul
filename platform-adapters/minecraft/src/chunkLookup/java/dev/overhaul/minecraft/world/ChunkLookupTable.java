package dev.overhaul.minecraft.world;

import java.util.Arrays;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Direct-mapped lookup behind the game's four-entry chunk cache, used on the server thread only. */
public final class ChunkLookupTable {
    private static final int SIZE = 256;

    private final long[] positions = new long[SIZE];
    private final ChunkStatus[] statuses = new ChunkStatus[SIZE];
    private final ChunkAccess[] chunks = new ChunkAccess[SIZE];
    private boolean occupied;

    /** The game's packed chunk key; its method has a different name on each version. */
    public static long position(int x, int z) {
        return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
    }

    public ChunkAccess get(long position, ChunkStatus status) {
        int slot = slot(position, status);
        return positions[slot] == position && statuses[slot] == status ? chunks[slot] : null;
    }

    public void put(long position, ChunkStatus status, ChunkAccess chunk) {
        int slot = slot(position, status);
        positions[slot] = position;
        statuses[slot] = status;
        chunks[slot] = chunk;
        occupied = true;
    }

    public void clear() {
        if (!occupied) return;
        Arrays.fill(statuses, null);
        Arrays.fill(chunks, null);
        occupied = false;
    }

    private static int slot(long position, ChunkStatus status) {
        long mixed = (position ^ position >>> 29) * 0x9E3779B97F4A7C15L;
        return ((int) (mixed >>> 40) ^ status.getIndex() * 31) & SIZE - 1;
    }
}
