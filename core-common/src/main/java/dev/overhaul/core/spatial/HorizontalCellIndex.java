package dev.overhaul.core.spatial;

import java.util.Arrays;

/**
 * Bulk-rebuilt grid of items over the horizontal plane. Rebuilding reuses its arrays
 * and queries walk primitive cursors, so per-query lookups allocate nothing.
 * Confined to one thread; a rebuild invalidates every cursor.
 */
public final class HorizontalCellIndex<T> {
    private static final int END = -1;
    private final int shift;
    private long[] slotKeys = new long[0];
    private int[] slotHeads = new int[0];
    private int[] next = new int[0];
    private Object[] items = new Object[0];
    private int count, capacity;

    public HorizontalCellIndex(int cellShift) {
        if (cellShift < 0 || cellShift > 24) throw new IllegalArgumentException("cell shift outside [0,24]");
        shift = cellShift;
    }

    /** Empties the index and reserves room for exactly {@code expected} additions. */
    public void begin(int expected) {
        if (expected < 0 || expected > 1 << 28) throw new IllegalArgumentException("invalid item count");
        Arrays.fill(items, 0, count, null);
        count = 0;
        capacity = expected;
        if (items.length < expected) { items = new Object[expected]; next = new int[expected]; }
        int slots = Math.max(2, Integer.highestOneBit(Math.max(1, expected * 2 - 1)) << 1);
        if (slotHeads.length < slots) { slotHeads = new int[slots]; slotKeys = new long[slots]; }
        Arrays.fill(slotHeads, END);
    }

    public void add(T item, double x, double z) {
        if (count == capacity) throw new IllegalStateException("index holds its reserved count");
        int slot = slot(key(cell(x), cell(z)), true);
        items[count] = item;
        next[count] = slotHeads[slot];
        slotHeads[slot] = count++;
    }

    public int cell(double coordinate) {
        return (int) Math.floor(coordinate) >> shift;
    }

    /** The first cursor in a cell, or a negative value when the cell is empty. */
    public int first(int cellX, int cellZ) {
        if (count == 0) return END;
        int slot = slot(key(cellX, cellZ), false);
        return slot < 0 ? END : slotHeads[slot];
    }

    public int next(int cursor) { return next[cursor]; }

    @SuppressWarnings("unchecked")
    public T item(int cursor) { return (T) items[cursor]; }

    private static long key(int cellX, int cellZ) {
        return (long) cellX << 32 | cellZ & 0xFFFFFFFFL;
    }

    private int slot(long key, boolean claim) {
        int mask = slotHeads.length - 1;
        long mixed = key * 0x9E3779B97F4A7C15L;
        int slot = (int) (mixed ^ mixed >>> 32) & mask;
        while (slotHeads[slot] != END) {
            if (slotKeys[slot] == key) return slot;
            slot = slot + 1 & mask;
        }
        if (!claim) return END;
        slotKeys[slot] = key;
        return slot;
    }
}
