package dev.overhaul.minecraft.world;

import dev.overhaul.core.concurrent.WorkerScheduler;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * One bounded parsing pool per server, shared across dimensions. Saturation uses
 * Minecraft's original asynchronous parse executor, never the submitting thread.
 * Only the parseChunk future's runnable is redirected; POI and world publication
 * remain in ChunkMap's original continuation graph.
 */
public final class ChunkParseExecutor implements Executor, AutoCloseable {
    private static final long CLOSED = Long.MIN_VALUE;
    private static final int QUEUED_PARSES = 128;
    private final Executor overflow;
    private final WorkerScheduler workers;
    private final int workerCount;
    private final AtomicLong lifecycle = new AtomicLong();
    private final AtomicLong owned = new AtomicLong();
    private final AtomicLong overflowed = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private boolean drained;

    public ChunkParseExecutor(Executor overflow) {
        this.overflow = Objects.requireNonNull(overflow, "overflow");
        workerCount = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        workers = new WorkerScheduler(workerCount, QUEUED_PARSES, "overhaul-chunk-parse");
    }

    @Override public void execute(Runnable command) {
        Task task = new Task(Objects.requireNonNull(command, "command"));
        reserve();
        try {
            try {
                workers.submit(() -> { task.run(); return null; });
                owned.incrementAndGet();
                return;
            } catch (RejectedExecutionException saturated) {
                // Closing can race an already reserved submission. That command
                // still belongs to this scope and must finish through overflow.
                overflowed.incrementAndGet();
            }
            overflow.execute(task);
        } catch (RuntimeException | Error failure) {
            task.rejected();
            throw failure;
        }
    }

    private void reserve() {
        for (;;) {
            long state = lifecycle.get();
            if (state < 0) throw new RejectedExecutionException("server chunk parsing is closed");
            if (state == Long.MAX_VALUE) throw new RejectedExecutionException("too many pending chunk parses");
            if (lifecycle.compareAndSet(state, state + 1)) return;
        }
    }

    /**
     * Counters describe runnable routing. Parse exceptions belong to the original
     * CompletableFuture; taskFailures counts dispatch/uncaught runnable failures.
     */
    public Map<String, Long> snapshot() {
        return Map.of("workers", (long) workerCount, "queueCapacity", (long) QUEUED_PARSES,
                "owned", owned.get(), "overflow", overflowed.get(), "completed", completed.get(),
                "taskFailures", failed.get(), "pending", lifecycle.get() & Long.MAX_VALUE);
    }

    /**
     * Called by the server after vanilla's ticket/save/unload drain and before
     * world resources close. Waits for parsing, including overflow, without waiting for any
     * main-thread publication continuation. The shared vanilla executor stays open.
     */
    @Override public synchronized void close() {
        if (drained) return;
        for (;;) {
            long state = lifecycle.get();
            if (state < 0 || lifecycle.compareAndSet(state, state | CLOSED)) break;
        }
        workers.close();
        boolean interrupted = false;
        while ((lifecycle.get() & Long.MAX_VALUE) != 0) {
            LockSupport.parkNanos(1_000_000L);
            if (Thread.interrupted()) interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
        drained = true;
    }

    private final class Task implements Runnable {
        private final Runnable command;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private Task(Runnable command) { this.command = command; }

        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try { command.run(); }
            catch (RuntimeException | Error failure) {
                failed.incrementAndGet();
                throw failure;
            } finally {
                completed.incrementAndGet();
                lifecycle.decrementAndGet();
            }
        }

        private void rejected() {
            if (claimed.compareAndSet(false, true)) {
                failed.incrementAndGet();
                lifecycle.decrementAndGet();
            }
        }
    }
}
