package dev.overhaul.adapter;

import dev.overhaul.core.world.TickWorkQueue;

/** Server-thread cooperative work; deliberately has no rendering or native dependencies. */
public final class ServerRuntime implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final long budgetNanos;
    private TickWorkQueue tasks;
    private long ticks;

    public ServerRuntime(int capacity,long budgetNanos) {
        if (budgetNanos<0) throw new IllegalArgumentException("negative tick budget");
        tasks=new TickWorkQueue(capacity);
        this.budgetNanos=budgetNanos;
    }

    public boolean enqueue(Runnable task) { requireOwner(); return tasks.offer(task); }
    public void tick() { requireOwner(); ticks++; tasks.run(budgetNanos); }
    public long tickCount() { requireOwner(); return ticks; }

    private void requireOwner() {
        if (Thread.currentThread()!=owner) throw new IllegalStateException("runtime belongs to server thread");
        if (tasks==null) throw new IllegalStateException("runtime is closed");
    }

    @Override public void close() {
        if (tasks==null) return;
        requireOwner(); tasks=null;
    }
}
