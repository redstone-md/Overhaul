package dev.overhaul.minecraft.world;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.SectionPos;

/** Derived section counts per X column, used only to choose a traversal strategy. */
final class SectionColumnOccupancy {
    private static final int LONG_COLUMN_MINIMUM = 32;
    private final Int2IntOpenHashMap counts = new Int2IntOpenHashMap();
    private int longColumns;
    private boolean consistent = true;

    SectionColumnOccupancy(Long2ObjectMap<?> sections) {
        LongIterator keys = sections.keySet().iterator();
        while (keys.hasNext()) created(keys.nextLong());
    }

    void created(long key) {
        int x = SectionPos.x(key);
        int previous = counts.get(x);
        if (previous == Integer.MAX_VALUE) { consistent = false; return; }
        counts.put(x, previous + 1);
        if (previous == LONG_COLUMN_MINIMUM) longColumns++;
    }

    void removing(long key) {
        int x = SectionPos.x(key);
        int previous = counts.get(x);
        if (previous <= 0) { consistent = false; return; }
        if (previous == LONG_COLUMN_MINIMUM + 1) longColumns--;
        if (previous == 1) counts.remove(x);
        else counts.put(x, previous - 1);
    }

    boolean hasLongColumns() { return consistent && longColumns != 0; }
    boolean worthSeeking(int x, long intersectingCells) {
        return consistent && counts.get(x) > Math.max(LONG_COLUMN_MINIMUM, 4L * intersectingCells);
    }

    int columns() { return counts.size(); }
    int longColumns() { return longColumns; }
}
