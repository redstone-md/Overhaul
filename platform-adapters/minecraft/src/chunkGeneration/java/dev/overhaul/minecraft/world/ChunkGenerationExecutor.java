package dev.overhaul.minecraft.world;

import dev.overhaul.core.concurrent.BoundedWorkerExecutor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Actual biome/terrain supplier execution; original generation dependency queues remain authoritative. */
public final class ChunkGenerationExecutor implements AutoCloseable {
    private final Object lifecycle = new Object();
    private final Set<World> worlds = new HashSet<>();
    private final BoundedWorkerExecutor workers;
    private boolean closing;

    public ChunkGenerationExecutor(Executor vanillaExecutor) {
        int count = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        workers = new BoundedWorkerExecutor(count, 128, "overhaul-chunk-generate", vanillaExecutor);
    }

    public World createWorld(GenerationStateAccess state, Runnable stopGeneration) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(stopGeneration, "stopGeneration");
        synchronized (lifecycle) {
            if (closing) throw new RejectedExecutionException("server chunk generation is closed");
            World world = new World(this, workers, state, stopGeneration);
            try {
                state.overhaul$bindGeneration(world);
                worlds.add(world);
                return world;
            } catch (RuntimeException | Error failure) {
                try { world.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    private void retired(World world) { synchronized (lifecycle) { worlds.remove(world); } }

    /** Close remaining original generation dispatchers before draining the shared workers. */
    @Override public void close() {
        workers.requireOutsideTask();
        ArrayList<World> remaining;
        synchronized (lifecycle) {
            closing = true;
            remaining = new ArrayList<>(worlds);
        }
        Throwable failure = null;
        for (World world : remaining) {
            try { world.close(); }
            catch (RuntimeException | Error problem) { failure = accumulate(failure, problem); }
        }
        try { workers.close(); }
        catch (RuntimeException | Error problem) { failure = accumulate(failure, problem); }
        rethrow(failure);
    }

    private static Throwable accumulate(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    /** One actual ChunkMap/WorldGenContext, independent of other dimensions using these workers. */
    public static final class World implements AutoCloseable {
        private final Object lifecycle = new Object();
        private final Object closure = new Object();
        private final BoundedWorkerExecutor workers;
        private ChunkGenerationExecutor owner;
        private GenerationStateAccess state;
        private Runnable stopGeneration;
        private BoundedWorkerExecutor.Scope terrain;
        private BoundedWorkerExecutor.Scope biomes;
        private boolean closing;
        private boolean drained;

        private World(ChunkGenerationExecutor owner, BoundedWorkerExecutor workers,
                GenerationStateAccess state, Runnable stopGeneration) {
            this.owner = owner;
            this.workers = workers;
            this.state = state;
            this.stopGeneration = stopGeneration;
        }

        /** Original named executor is retained as this stage's asynchronous overflow route. */
        public Executor executor(String stage, Executor vanillaExecutor) {
            synchronized (lifecycle) {
                if (closing) throw new RejectedExecutionException("world chunk generation is closed");
                if ("buildTerrain".equals(stage)) {
                    if (terrain == null) terrain = workers.newScope(vanillaExecutor);
                    return terrain;
                }
                if ("createBiomes".equals(stage)) {
                    if (biomes == null) biomes = workers.newScope(vanillaExecutor);
                    return biomes;
                }
                return vanillaExecutor;
            }
        }

        @Override public void close() {
            workers.requireOutsideTask();
            synchronized (closure) {
                if (drained) return;
                synchronized (lifecycle) { closing = true; }
                Throwable failure = null;
                try { stopGeneration.run(); }
                catch (RuntimeException | Error problem) { failure = problem; }
                // No lifecycle monitor is held across joins. Completing a supplier
                // may synchronously attempt a next stage, which must reject rather
                // than wait behind the owner that is draining that same supplier.
                failure = closeScope(terrain, failure);
                failure = closeScope(biomes, failure);
                try { state.overhaul$unbindGeneration(this); }
                catch (RuntimeException | Error problem) { failure = accumulate(failure, problem); }
                owner.retired(this);
                synchronized (lifecycle) {
                    stopGeneration = null;
                    state = null;
                    owner = null;
                    terrain = null;
                    biomes = null;
                    drained = true;
                }
                rethrow(failure);
            }
        }

        private static Throwable closeScope(BoundedWorkerExecutor.Scope scope, Throwable failure) {
            if (scope != null) {
                try { scope.close(); }
                catch (RuntimeException | Error problem) { return accumulate(failure, problem); }
            }
            return failure;
        }
    }
}
