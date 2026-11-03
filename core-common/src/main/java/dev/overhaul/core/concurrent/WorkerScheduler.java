package dev.overhaul.core.concurrent;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Bounded workers with non-blocking admission and graceful drain on close. */
public final class WorkerScheduler implements AutoCloseable {
    private final BoundedMpmcQueue<Job<?>> jobs;
    private final Thread[] workers;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicInteger submissions = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();

    public WorkerScheduler(int workerCount, int queueCapacity, String threadPrefix) {
        if (workerCount < 1) throw new IllegalArgumentException("workerCount must be positive");
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        jobs = new BoundedMpmcQueue<Job<?>>(queueCapacity);
        workers = new Thread[workerCount];
        int started = 0;
        try {
            for (int i = 0; i < workerCount; i++) {
                workers[i] = new Thread(this::work, threadPrefix + "-" + i);
                workers[i].setDaemon(true);
                workers[i].start();
                started++;
            }
        } catch (RuntimeException | Error failure) {
            // The failed/uncreated suffix is never joined. Previously started
            // workers must stop before this inaccessible scheduler is abandoned.
            try { stopWorkers(started); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public <T> CompletableFuture<T> submit(Callable<T> operation) {
        return submitOwned(operation, null);
    }

    /** Transfers input ownership even if admission fails or the queued result is canceled. */
    public <T> CompletableFuture<T> submitOwned(Callable<T> operation, AutoCloseable input) {
        Objects.requireNonNull(operation, "operation");
        submissions.incrementAndGet();
        boolean admitted = false;
        try {
            if (!accepting.get()) throw new RejectedExecutionException("scheduler is closed");
            Job<T> job = new Job<T>(operation, input);
            pending.incrementAndGet();
            if (!jobs.offer(job)) {
                pending.decrementAndGet();
                throw new RejectedExecutionException("worker queue is full");
            }
            admitted = true;
            for (Thread worker : workers) LockSupport.unpark(worker);
            return job.future;
        } finally {
            submissions.decrementAndGet();
            if (!admitted) dispose(input);
        }
    }

    private void work() {
        while (accepting.get() || submissions.get() != 0 || pending.get() != 0) {
            Job<?> job = jobs.poll();
            if (job == null) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            } else {
                try { job.run(); } finally { pending.decrementAndGet(); }
            }
        }
    }

    public int pendingTasks() { return pending.get(); }

    @Override public void close() { stopWorkers(workers.length); }

    private void stopWorkers(int started) {
        for (int i = 0; i < started; i++) {
            Thread worker = workers[i];
            if (worker == Thread.currentThread()) {
                throw new IllegalStateException("worker cannot join its own scheduler");
            }
        }
        accepting.set(false);
        for (int i = 0; i < started; i++) LockSupport.unpark(workers[i]);
        boolean interrupted = false;
        for (int i = 0; i < started; i++) {
            Thread worker = workers[i];
            for (;;) {
                try { worker.join(); break; }
                catch (InterruptedException exception) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class Job<T> {
        private final Callable<T> operation;
        private final AutoCloseable input;
        private final CompletableFuture<T> future = new CompletableFuture<T>();

        private Job(Callable<T> operation, AutoCloseable input) { this.operation = operation; this.input = input; }

        private void run() {
            T result = null;
            try {
                if (future.isCancelled()) return;
                result = operation.call();
                if (!future.complete(result)) dispose(result);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally { dispose(input); }
        }
    }

    private static void dispose(Object value) {
        if (value instanceof AutoCloseable) {
            try { ((AutoCloseable) value).close(); }
            catch (Exception ignored) { /* Cleanup has no result recipient. */ }
        }
    }
}
