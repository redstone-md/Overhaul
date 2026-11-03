package dev.overhaul.core.concurrent;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Lock-free MPMC queue with bounded, CAS-based admission. */
public final class BoundedMpmcQueue<E> {
    private final ConcurrentLinkedQueue<E> entries = new ConcurrentLinkedQueue<E>();
    private final AtomicInteger reservations = new AtomicInteger();
    private final int capacity;

    public BoundedMpmcQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public boolean offer(E value) {
        Objects.requireNonNull(value, "value");
        for (;;) {
            int used = reservations.get();
            if (used == capacity) return false;
            if (reservations.compareAndSet(used, used + 1)) break;
        }
        entries.offer(value);
        return true;
    }

    public E poll() {
        E result = entries.poll();
        if (result != null) reservations.decrementAndGet();
        return result;
    }
}
