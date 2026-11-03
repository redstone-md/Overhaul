package dev.overhaul.core.concurrent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded owned workers with asynchronous overflow and independently drainable
 * consumer scopes. Overflow must itself be an asynchronous executor. All admitted
 * commands, including overflow, belong to both their scope and this owner.
 */
public final class BoundedWorkerExecutor implements Executor, AutoCloseable {
    private final Counter lifetime = new Counter();
    private final ThreadLocal<Scope> executing = new ThreadLocal<Scope>();
    private final Object closure = new Object();
    private final AtomicLong owned = new AtomicLong();
    private final AtomicLong overflowed = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final WorkerScheduler workers;
    private final Scope defaultScope;
    private final int workerCount;
    private final int queueCapacity;
    private boolean drained;

    public BoundedWorkerExecutor(int workerCount, int queueCapacity, String threadPrefix, Executor overflow) {
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        // Allocate the default scope before starting workers: scope construction
        // failure must not orphan an otherwise inaccessible running pool.
        defaultScope = new Scope(Objects.requireNonNull(overflow, "overflow"));
        workers = new WorkerScheduler(workerCount, queueCapacity, threadPrefix);
    }

    @Override public void execute(Runnable command) { defaultScope.execute(command); }

    public Scope newScope(Executor overflow) {
        if (lifetime.isClosed()) throw new RejectedExecutionException("worker executor is closed");
        return new Scope(Objects.requireNonNull(overflow, "overflow"));
    }

    /** Runnable routing only; exceptions stored by a command's future stay in that future. */
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        result.put("workers", (long) workerCount);
        result.put("queueCapacity", (long) queueCapacity);
        result.put("owned", owned.get());
        result.put("overflow", overflowed.get());
        result.put("completed", completed.get());
        result.put("taskFailures", failed.get());
        result.put("pending", lifetime.pending());
        return Collections.unmodifiableMap(result);
    }

    /** Check before acquiring a producer's shutdown monitor or closing its serial strand. */
    public void requireOutsideTask() {
        if (executing.get() != null) throw new IllegalStateException("worker command cannot join its own executor");
    }

    /** Stops admission and drains owned and overflow commands; never closes the overflow executor. */
    @Override public void close() {
        // Check before taking the closure monitor: an executing command must not
        // block behind an owner already waiting for that very command to return.
        requireOutsideTask();
        synchronized (closure) {
            if (drained) return;
            lifetime.stopAdmission();
            workers.close();
            lifetime.awaitEmpty();
            drained = true;
        }
    }

    public final class Scope implements Executor, AutoCloseable {
        private final Executor overflow;
        private final Counter commands = new Counter();

        private Scope(Executor overflow) { this.overflow = overflow; }

        @Override public void execute(Runnable command) {
            Task task = new Task(this, Objects.requireNonNull(command, "command"));
            lifetime.reserve();
            try { commands.reserve(); }
            catch (RuntimeException | Error failure) { lifetime.release(); throw failure; }
            try {
                try {
                    workers.submit(() -> { task.run(); return null; });
                    owned.incrementAndGet();
                    return;
                } catch (RejectedExecutionException saturated) {
                    // Owner closure may overtake a reserved submission. The
                    // reservation still covers its asynchronous overflow route.
                    overflowed.incrementAndGet();
                }
                overflow.execute(task);
            } catch (RuntimeException | Error failure) {
                task.rejected();
                throw failure;
            }
        }

        /**
         * The caller must first stop the authoritative producer/serial strand.
         * Closing a scope never stops other scopes or their shared worker pool.
         */
        @Override public void close() {
            requireOutsideTask();
            commands.stopAdmission();
            commands.awaitEmpty();
        }
    }

    private final class Task implements Runnable {
        private final Scope scope;
        private final Runnable command;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private Task(Scope scope, Runnable command) { this.scope = scope; this.command = command; }

        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            Scope previous = null;
            try {
                previous = executing.get();
                executing.set(scope);
                command.run();
            } catch (RuntimeException | Error failure) {
                failed.incrementAndGet();
                throw failure;
            } finally {
                try {
                    if (previous == null) executing.remove();
                    else executing.set(previous);
                } finally {
                    completed.incrementAndGet();
                    release();
                }
            }
        }

        private void rejected() {
            if (claimed.compareAndSet(false, true)) {
                failed.incrementAndGet();
                release();
            }
        }

        private void release() { scope.commands.release(); lifetime.release(); }
    }

    /** Closure and reservations share a CAS, covering admission versus shutdown. */
    private static final class Counter {
        private static final long CLOSED = Long.MIN_VALUE;
        private final AtomicLong state = new AtomicLong();

        private void reserve() {
            for (;;) {
                long current = state.get();
                if (current < 0) throw new RejectedExecutionException("worker scope is closed");
                if (current == Long.MAX_VALUE) throw new RejectedExecutionException("too many worker commands");
                if (state.compareAndSet(current, current + 1)) return;
            }
        }

        private void release() { state.decrementAndGet(); }
        private long pending() { return state.get() & Long.MAX_VALUE; }
        private boolean isClosed() { return state.get() < 0; }

        private void stopAdmission() {
            for (;;) {
                long current = state.get();
                if (current < 0 || state.compareAndSet(current, current | CLOSED)) return;
            }
        }

        private void awaitEmpty() {
            boolean interrupted = false;
            while (pending() != 0) {
                LockSupport.parkNanos(1_000_000L);
                if (Thread.interrupted()) interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
