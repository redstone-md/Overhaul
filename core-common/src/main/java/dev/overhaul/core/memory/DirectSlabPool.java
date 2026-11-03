package dev.overhaul.core.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Fixed native-memory budget; leases exclusively own one slab until returned. */
public final class DirectSlabPool implements AutoCloseable {
    private final ByteBuffer storage;
    private final int slabBytes;
    private final AtomicIntegerArray occupied;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DirectSlabPool(int slabBytes, int slabCount) {
        if (slabBytes < 1 || slabCount < 1) throw new IllegalArgumentException("positive sizes required");
        storage = ByteBuffer.allocateDirect(Math.multiplyExact(slabBytes, slabCount))
                .order(ByteOrder.nativeOrder());
        this.slabBytes = slabBytes;
        occupied = new AtomicIntegerArray(slabCount);
    }

    public Lease acquire(int bytes) {
        if (bytes < 0 || bytes > slabBytes) throw new IllegalArgumentException("request exceeds slab");
        if (closed.get()) throw new IllegalStateException("pool is closed");
        int start = (cursor.getAndIncrement() & Integer.MAX_VALUE) % occupied.length();
        for (int offset = 0; offset < occupied.length(); offset++) {
            int index = (start + offset) % occupied.length();
            if (occupied.compareAndSet(index, 0, 1)) {
                active.incrementAndGet();
                if (closed.get()) {
                    release(index);
                    throw new IllegalStateException("pool is closed");
                }
                ByteBuffer view = storage.duplicate().order(ByteOrder.nativeOrder());
                view.position(index * slabBytes).limit(index * slabBytes + bytes);
                return new Lease(this, index, view.slice().order(ByteOrder.nativeOrder()));
            }
        }
        throw new IllegalStateException("native slab budget exhausted");
    }

    public int activeLeases() { return active.get(); }
    public long capacityBytes() { return storage.capacity(); }
    public int slabBytes() { return slabBytes; }

    private void release(int index) {
        occupied.set(index, 0);
        active.decrementAndGet();
    }

    /** Prevents new acquisitions. Direct memory is reclaimed by the JVM after pool collection. */
    @Override public void close() { closed.set(true); }

    public static final class Lease implements AutoCloseable {
        private final DirectSlabPool owner;
        private final int index;
        private final ByteBuffer data;
        private final AtomicBoolean returned = new AtomicBoolean();

        private Lease(DirectSlabPool owner, int index, ByteBuffer data) {
            this.owner = owner;
            this.index = index;
            this.data = data;
        }

        /** Borrowed view, valid only until this lease is closed; do not retain across upload completion. */
        public ByteBuffer buffer() {
            if (returned.get()) throw new IllegalStateException("lease was returned");
            return data.duplicate().order(ByteOrder.nativeOrder());
        }

        @Override public void close() {
            if (returned.compareAndSet(false, true)) owner.release(index);
        }
    }
}
