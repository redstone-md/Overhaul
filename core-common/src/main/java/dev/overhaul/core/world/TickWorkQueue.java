package dev.overhaul.core.world;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Main-thread cooperative queue. Tasks run once in FIFO order within a measured tick budget. */
public final class TickWorkQueue {
    private final Thread owner = Thread.currentThread();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();
    private final LongSupplier clock;
    private final int capacity;

    public TickWorkQueue(int capacity) { this(capacity, System::nanoTime); }

    public TickWorkQueue(int capacity, LongSupplier clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean offer(Runnable task) {
        checkOwner();
        Objects.requireNonNull(task, "task");
        if (tasks.size() == capacity) return false;
        tasks.addLast(task);
        return true;
    }

    /** Tasks must cooperate: this cannot preempt a task that exceeds its remaining budget. */
    public int run(long budgetNanos) {
        checkOwner();
        if (budgetNanos < 0) throw new IllegalArgumentException("negative budget");
        long start = clock.getAsLong();
        int executed = 0;
        int initialSize = tasks.size();
        while (executed < initialSize && !tasks.isEmpty() && clock.getAsLong() - start < budgetNanos) {
            Runnable task = tasks.removeFirst();
            task.run();
            executed++;
        }
        return executed;
    }

    public int pendingTasks() { checkOwner(); return tasks.size(); }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("tick work belongs to the simulation thread");
    }
}
