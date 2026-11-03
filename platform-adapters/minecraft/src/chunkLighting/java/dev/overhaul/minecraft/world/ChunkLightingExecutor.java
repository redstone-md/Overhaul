package dev.overhaul.minecraft.world;

import dev.overhaul.core.concurrent.BoundedWorkerExecutor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Server-scoped lighting workers; each mutable light engine retains Minecraft's
 * own serial mailbox and chunk priority dispatcher. Only strand wakeups use
 * the bounded pool. Saturation uses that strand's original asynchronous executor.
 */
public final class ChunkLightingExecutor implements AutoCloseable {
    private final Object lifecycle = new Object();
    private final Set<Strand<?>> strands = new HashSet<>();
    private final BoundedWorkerExecutor workers;
    private boolean closing;

    public ChunkLightingExecutor(Executor vanillaExecutor) {
        int workerCount = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
        workers = new BoundedWorkerExecutor(workerCount, 128, "overhaul-chunk-light", vanillaExecutor);
    }

    public <E> Strand<E> createStrand(Executor vanillaExecutor, String name,
            BiFunction<Executor, String, E> factory, Consumer<E> closer) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(closer, "closer");
        synchronized (lifecycle) {
            if (closing) throw new RejectedExecutionException("server chunk lighting is closed");
            BoundedWorkerExecutor.Scope scope = workers.newScope(vanillaExecutor);
            E executor = null;
            try {
                executor = Objects.requireNonNull(factory.apply(scope, name), "serial executor");
                Strand<E> strand = new Strand<>(this, executor, scope, closer);
                strands.add(strand);
                return strand;
            } catch (RuntimeException | Error failure) {
                if (executor != null) try { closer.accept(executor); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                try { scope.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    /**
     * Normal server shutdown has already closed every dimension. On exceptional
     * shutdown, close remaining original strands first: an open strand would
     * otherwise resubmit itself into a closing worker pool and strand its queue.
     */
    @Override public void close() {
        workers.requireOutsideTask();
        ArrayList<Strand<?>> remaining;
        synchronized (lifecycle) {
            closing = true;
            remaining = new ArrayList<>(strands);
        }
        Throwable failure = null;
        for (Strand<?> strand : remaining) {
            try { strand.close(); }
            catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        try { workers.close(); }
        catch (RuntimeException | Error problem) {
            if (failure == null) failure = problem;
            else failure.addSuppressed(problem);
        }
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    private void retired(Strand<?> strand) { synchronized (lifecycle) { strands.remove(strand); } }

    public static final class Strand<E> implements AutoCloseable {
        private final Object closure = new Object();
        private final BoundedWorkerExecutor workers;
        private ChunkLightingExecutor owner;
        private E executor;
        private BoundedWorkerExecutor.Scope scope;
        private Consumer<E> closer;
        private boolean closed;

        private Strand(ChunkLightingExecutor owner, E executor, BoundedWorkerExecutor.Scope scope, Consumer<E> closer) {
            this.owner = owner;
            workers = owner.workers;
            this.executor = executor;
            this.scope = scope;
            this.closer = closer;
        }

        public E executor() {
            synchronized (closure) {
                if (closed) throw new IllegalStateException("lighting strand is closed");
                return executor;
            }
        }

        /** Original strand closes before waiting for queued/active backing wakeups. */
        @Override public void close() {
            workers.requireOutsideTask();
            synchronized (closure) {
                if (closed) return;
                try { closer.accept(executor); }
                catch (RuntimeException | Error failure) {
                    try { scope.close(); }
                    catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
                scope.close();
                owner.retired(this);
                executor = null;
                scope = null;
                closer = null;
                owner = null;
                closed = true;
            }
        }
    }
}
