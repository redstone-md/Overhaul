package dev.overhaul.core.spatial;

import java.util.Objects;
import java.util.function.LongConsumer;

/** Owner-thread point index; radius queries allocate no per-cell or per-hit objects. */
public final class SpatialHash {
    private final Thread owner = Thread.currentThread();
    private final double cellSize;
    private final LongTable<Entry> entities = new LongTable<Entry>();
    private final LongTable<Entry> cells = new LongTable<Entry>();

    public SpatialHash(double cellSize) {
        if (!Double.isFinite(cellSize) || cellSize <= 0) throw new IllegalArgumentException("positive cell size required");
        this.cellSize = cellSize;
    }

    public void update(long id, double x, double y, double z) {
        checkOwner();
        long key = cellKey(coordinate(x), coordinate(y), coordinate(z));
        Entry entry = entities.get(id);
        if (entry == null) {
            entry = new Entry(id);
            entities.put(id, entry);
            attach(entry, key);
        } else if (entry.cell != key) {
            detach(entry);
            attach(entry, key);
        }
        entry.x = x; entry.y = y; entry.z = z;
    }

    public void remove(long id) {
        checkOwner();
        Entry entry = entities.get(id);
        if (entry != null) { detach(entry); entities.remove(id); }
    }

    /** Visitor must not mutate this index while traversal is in progress. */
    public int querySphere(double x, double y, double z, double radius, LongConsumer visitor) {
        checkOwner();
        Objects.requireNonNull(visitor, "visitor");
        if (!Double.isFinite(radius) || radius < 0) throw new IllegalArgumentException("finite nonnegative radius required");
        int minX = coordinate(x - radius), maxX = coordinate(x + radius);
        int minY = coordinate(y - radius), maxY = coordinate(y + radius);
        int minZ = coordinate(z - radius), maxZ = coordinate(z + radius);
        cellKey(minX, minY, minZ); cellKey(maxX, maxY, maxZ);
        long count = Math.multiplyExact(Math.multiplyExact((long) maxX - minX + 1,
                (long) maxY - minY + 1), (long) maxZ - minZ + 1);
        if (count > 1_000_000) throw new IllegalArgumentException("query exceeds cell budget");
        double squared = radius * radius;
        int hits = 0;
        for (int cy = minY; cy <= maxY; cy++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cx = minX; cx <= maxX; cx++) {
                    Entry entry = cells.get(cellKey(cx, cy, cz));
                    while (entry != null) {
                        double dx = entry.x - x, dy = entry.y - y, dz = entry.z - z;
                        if (dx * dx + dy * dy + dz * dz <= squared) { visitor.accept(entry.id); hits++; }
                        entry = entry.next;
                    }
                }
            }
        }
        return hits;
    }

    public int size() { checkOwner(); return entities.size(); }

    private void attach(Entry entry, long cell) {
        entry.cell = cell;
        entry.next = cells.get(cell);
        if (entry.next != null) entry.next.previous = entry;
        cells.put(cell, entry);
    }

    private void detach(Entry entry) {
        if (entry.previous != null) entry.previous.next = entry.next;
        else if (entry.next != null) cells.put(entry.cell, entry.next);
        else cells.remove(entry.cell);
        if (entry.next != null) entry.next.previous = entry.previous;
        entry.previous = null; entry.next = null;
    }

    private int coordinate(double value) {
        double coordinate = Math.floor(value / cellSize);
        if (!Double.isFinite(coordinate) || coordinate < Integer.MIN_VALUE || coordinate > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("coordinate outside index range");
        }
        return (int) coordinate;
    }

    private static long cellKey(int x, int y, int z) {
        if (x < -2097152 || x > 2097151 || z < -2097152 || z > 2097151 || y < -524288 || y > 524287) {
            throw new IllegalArgumentException("cell outside 22/20/22-bit index range");
        }
        return ((long) x & 0x3FFFFF) | (((long) z & 0x3FFFFF) << 22) | (((long) y & 0xFFFFF) << 44);
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("index belongs to the simulation thread");
    }

    private static final class Entry {
        private final long id;
        private long cell;
        private double x, y, z;
        private Entry previous, next;
        private Entry(long id) { this.id = id; }
    }
}
