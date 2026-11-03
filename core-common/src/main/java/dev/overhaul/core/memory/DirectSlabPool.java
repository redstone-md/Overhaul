package dev.overhaul.core.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Fixed native-memory budget; leases exclusively own one slab until returned. */
public final class DirectSlabPool implements AutoCloseable {
    private static final int CLOSED = Integer.MIN_VALUE;
    private static final DirectBufferAllocator JVM_ALLOCATOR = new DirectBufferAllocator() {
        @Override public ByteBuffer allocate(int bytes) { return ByteBuffer.allocateDirect(bytes); }
        @Override public void release(ByteBuffer allocation) { /* Reclaimed by the JVM cleaner. */ }
    };

    private volatile ByteBuffer storage;
    private final DirectBufferAllocator allocator;
    private final int capacityBytes;
    private final int slabBytes;
    private final AtomicIntegerArray occupied;
    private final AtomicInteger cursor = new AtomicInteger();
    // Reservation and closure share one CAS: close cannot retire storage while an
    // acquiring thread has permission to construct a lease but has not claimed a slot.
    private final AtomicInteger lifecycle = new AtomicInteger();
    private final AtomicBoolean retired = new AtomicBoolean();

    public DirectSlabPool(int slabBytes, int slabCount) {
        this(slabBytes, slabCount, JVM_ALLOCATOR);
    }

    public DirectSlabPool(int slabBytes, int slabCount, DirectBufferAllocator allocator) {
        if (slabBytes < 1 || slabCount < 1) throw new IllegalArgumentException("positive sizes required");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        capacityBytes = Math.multiplyExact(slabBytes, slabCount);
        this.slabBytes = slabBytes;
        occupied = new AtomicIntegerArray(slabCount);
        ByteBuffer allocation = Objects.requireNonNull(allocator.allocate(capacityBytes), "allocation");
        try {
            if (!allocation.isDirect() || allocation.isReadOnly() || allocation.capacity() != capacityBytes) {
                throw new IllegalArgumentException("allocator must return a writable direct buffer of exact capacity");
            }
            storage = allocation;
        } catch (RuntimeException | Error failure) {
            try { allocator.release(allocation); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Lease acquire(int bytes) {
        if (bytes < 0 || bytes > slabBytes) throw new IllegalArgumentException("request exceeds slab");
        reserve();
        int claimed = -1;
        try {
            int index = (cursor.getAndIncrement() & Integer.MAX_VALUE) % occupied.length();
            for (int offset = 0; offset < occupied.length(); offset++) {
                if (occupied.compareAndSet(index, 0, 1)) {
                    claimed = index;
                    ByteBuffer view = storage.duplicate().order(ByteOrder.nativeOrder());
                    view.clear();
                    view.position(index * slabBytes).limit(index * slabBytes + bytes);
                    return new Lease(this, index, view.slice().order(ByteOrder.nativeOrder()));
                }
                if (++index == occupied.length()) index = 0;
            }
            throw new IllegalStateException("native slab budget exhausted");
        } catch (RuntimeException | Error failure) {
            if (claimed >= 0) occupied.set(claimed, 0);
            try { returnReservation(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Includes acquisitions currently constructing their exclusive lease. */
    public int activeLeases() { return lifecycle.get() & Integer.MAX_VALUE; }
    public long capacityBytes() { return capacityBytes; }
    public int slabBytes() { return slabBytes; }

    private void reserve() {
        for (;;) {
            int state = lifecycle.get();
            if (state < 0) throw new IllegalStateException("pool is closed");
            if (state == occupied.length()) throw new IllegalStateException("native slab budget exhausted");
            if (lifecycle.compareAndSet(state, state + 1)) return;
        }
    }

    private void release(int index) {
        occupied.set(index, 0);
        returnReservation();
    }

    private void returnReservation() {
        if (lifecycle.decrementAndGet() == CLOSED) retire();
    }

    /**
     * Rejects new acquisitions without waiting for outstanding leases. The allocator
     * receives its allocation after the last lease returns. The default JVM allocator
     * still relies on cleaner reclamation; explicit allocators can free immediately.
     */
    @Override public void close() {
        for (;;) {
            int state = lifecycle.get();
            if (state < 0) return;
            if (lifecycle.compareAndSet(state, state | CLOSED)) {
                if (state == 0) retire();
                return;
            }
        }
    }

    private void retire() {
        if (retired.compareAndSet(false, true)) {
            ByteBuffer allocation = storage;
            storage = null;
            allocator.release(allocation);
        }
    }

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

        /**
         * Borrowed view valid only until this lease closes. The caller must finish all
         * reads/writes before closing; using a view concurrently with close is invalid.
         */
        public ByteBuffer buffer() {
            if (returned.get()) throw new IllegalStateException("lease was returned");
            return data.duplicate().order(ByteOrder.nativeOrder());
        }

        @Override public void close() {
            if (returned.compareAndSet(false, true)) owner.release(index);
        }
    }
}
