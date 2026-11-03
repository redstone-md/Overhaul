package dev.overhaul.minecraft.world;

import dev.overhaul.core.concurrent.BoundedWorkerExecutor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.util.thread.ConsecutiveExecutor;

/**
 * Server-scoped lighting workers; each mutable light engine retains Minecraft's
 * own ConsecutiveExecutor and chunk priority dispatcher. Only strand wakeups use
 * the bounded pool. Saturation uses that strand's original asynchronous executor.
 */
public final class ChunkLightingExecutor implements AutoCloseable {
    private final Object lifecycle = new Object();
    private final Set<Strand> strands = new HashSet<>();
    private final BoundedWorkerExecutor workers;
    private boolean closing;

    public ChunkLightingExecutor(Executor vanillaExecutor) {
        int workerCount = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
        workers = new BoundedWorkerExecutor(workerCount, 128, "overhaul-chunk-light", vanillaExecutor);
    }

    public Strand createStrand(Executor vanillaExecutor, String name) {
        Objects.requireNonNull(name, "name");
        synchronized (lifecycle) {
            if (closing) throw new RejectedExecutionException("server chunk lighting is closed");
            BoundedWorkerExecutor.Scope scope = workers.newScope(vanillaExecutor);
            ConsecutiveExecutor executor = null;
            try {
                executor = new ConsecutiveExecutor(scope, name);
                Strand strand = new Strand(executor, scope);
                strands.add(strand);
                return strand;
            } catch (RuntimeException | Error failure) {
                if (executor != null) try { executor.close(); }
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
        ArrayList<Strand> remaining;
        synchronized (lifecycle) {
            closing = true;
            remaining = new ArrayList<>(strands);
        }
        Throwable failure = null;
        for (Strand strand : remaining) {
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

    public final class Strand implements AutoCloseable {
        private final ConsecutiveExecutor executor;
        private final BoundedWorkerExecutor.Scope scope;

        private Strand(ConsecutiveExecutor executor, BoundedWorkerExecutor.Scope scope) {
            this.executor = executor;
            this.scope = scope;
        }

        public ConsecutiveExecutor executor() { return executor; }

        /** Original strand closes before waiting for queued/active backing wakeups. */
        @Override public void close() {
            try { executor.close(); }
            catch (RuntimeException | Error failure) {
                try { scope.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            scope.close();
            synchronized (lifecycle) { strands.remove(this); }
        }
    }
}
