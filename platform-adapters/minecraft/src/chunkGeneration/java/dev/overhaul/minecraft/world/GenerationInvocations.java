package dev.overhaul.minecraft.world;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Retains the original invocation and its final future, including asynchronous section release. */
final class GenerationInvocations {
    private final Object admission = new Object();
    private final ThreadLocal<Integer> depth = new ThreadLocal<>();
    private final AtomicLong pending = new AtomicLong();
    private Consumer<BooleanSupplier> awaiter;
    private Ticket first;
    private volatile Thread draining;
    private boolean closed;

    GenerationInvocations(Consumer<BooleanSupplier> awaiter) {
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter");
    }

    <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        Ticket ticket;
        synchronized (admission) {
            if (closed) {
                CompletableFuture<T> rejected = new CompletableFuture<>();
                rejected.completeExceptionally(new RejectedExecutionException("world generation invocation is closed"));
                return rejected;
            }
            ticket = new Ticket();
            ticket.next = first;
            if (first != null) first.previous = ticket;
            first = ticket;
            pending.incrementAndGet();
        }
        Integer previous = null;
        boolean restoreDepth = false;
        try {
            previous = depth.get();
            restoreDepth = true;
            depth.set(previous == null ? 1 : previous + 1);
            CompletableFuture<T> original = Objects.requireNonNull(operation.get(), "generation future");
            ticket.original = original;
            try { original.whenComplete((value, failure) -> ticket.close()); }
            catch (RuntimeException | Error failure) {
                // The method already returned a live future. Registration failure
                // cannot release its ticket; close will poll this original future.
                ticket.pollCompletion = true;
                throw failure;
            }
            return original;
        } catch (RuntimeException | Error failure) {
            if (ticket.original == null) ticket.close();
            throw failure;
        } finally {
            if (restoreDepth) {
                if (previous == null) depth.remove();
                else depth.set(previous);
            }
        }
    }

    boolean acceptsCurrent() { return depth.get() != null; }

    void requireOutsideInvocation() {
        if (acceptsCurrent() || draining == Thread.currentThread()) {
            throw new IllegalStateException("generation invocation cannot drain itself");
        }
    }

    void stopAdmission() { synchronized (admission) { closed = true; } }

    /** No admission or World lifecycle monitor is held while the game pumps its original loop. */
    void awaitEmpty() {
        if (finished()) { awaiter = null; return; }
        draining = Thread.currentThread();
        try {
            awaiter.accept(this::finished);
            if (!finished()) throw new IllegalStateException("generation awaiter returned before release");
            awaiter = null;
        } finally { draining = null; }
    }

    private boolean finished() {
        synchronized (admission) {
            Ticket ticket = first;
            while (ticket != null) {
                Ticket next = ticket.next;
                if (ticket.pollCompletion && ticket.original.isDone()) ticket.close();
                ticket = next;
            }
            return pending.get() == 0;
        }
    }

    private final class Ticket implements AutoCloseable {
        private final AtomicBoolean finished = new AtomicBoolean();
        private Ticket previous, next;
        private volatile CompletableFuture<?> original;
        private volatile boolean pollCompletion;
        @Override public void close() {
            if (!finished.compareAndSet(false, true)) return;
            synchronized (admission) {
                if (previous == null) first = next;
                else previous.next = next;
                if (next != null) next.previous = previous;
                previous = null;
                next = null;
                original = null;
                pending.decrementAndGet();
            }
        }
    }
}
