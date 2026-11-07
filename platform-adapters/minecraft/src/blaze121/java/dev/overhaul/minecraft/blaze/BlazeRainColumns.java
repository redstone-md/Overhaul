package dev.overhaul.minecraft.blaze;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Which rain columns already have an answer this client tick. Rain walks the 21 by 21 columns around the camera every
 * frame; in a 32 by 32 table the columns of a row never share a slot, and a changed position or level asks again. The
 * caller keeps the answers in its own array at {@link #slot}.
 */
public final class BlazeRainColumns {
    private final int[] keys = new int[32 * 32 * 3];
    private final long[] stamps = new long[32 * 32];
    /** Weak, so a level the player left is not kept alive by the table. */
    private WeakReference<Level> level = new WeakReference<>(null);
    private int slot;

    /** Whether the column at {@code pos} was answered this tick; either way {@link #slot} is then its slot. */
    public boolean fresh(Level level, BlockPos pos) {
        if (this.level.get() != level) {
            this.level = new WeakReference<>(level);
            Arrays.fill(stamps, Long.MIN_VALUE);
        }
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        slot = (x & 31) << 5 | z & 31;
        int key = slot * 3;
        if (stamps[slot] == level.getGameTime() && keys[key] == x && keys[key + 1] == y && keys[key + 2] == z) return true;
        keys[key] = x;
        keys[key + 1] = y;
        keys[key + 2] = z;
        stamps[slot] = level.getGameTime();
        return false;
    }

    public int slot() {
        return slot;
    }
}
