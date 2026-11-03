package dev.overhaul.core.world;

import dev.overhaul.core.concurrent.WorkerScheduler;
import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.mesh.ChunkMesh;
import dev.overhaul.core.mesh.ChunkMesher;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;

/** Snapshot-only asynchronous lighting and meshing; callers own completed mesh leases. */
public final class ChunkPipeline implements AutoCloseable {
    private final WorkerScheduler workers;
    private final DirectSlabPool pool;
    private final ChunkMesher mesher = new ChunkMesher();
    private final BlockLighting lighting = new BlockLighting();

    public ChunkPipeline(int workerCount, int queuedChunks, DirectSlabPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        workers = new WorkerScheduler(workerCount, queuedChunks, "overhaul-chunk");
    }

    public CompletableFuture<ChunkMesh> submit(ChunkSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return workers.submit(() -> mesher.mesh(snapshot, lighting.calculate(snapshot), pool));
    }

    /** Decoding, lighting and meshing all execute off-thread; packet ownership transfers on invocation. */
    public CompletableFuture<ChunkMesh> submitPacket(ChunkPacket packet) {
        Objects.requireNonNull(packet, "packet");
        return workers.submitOwned(() -> {
            ChunkSnapshot snapshot = packet.decode();
            return mesher.mesh(snapshot, lighting.calculate(snapshot), pool);
        }, packet);
    }

    /** Generator must use immutable inputs only and must not access live Minecraft world state. */
    public CompletableFuture<ChunkMesh> submitGeneration(Callable<ChunkSnapshot> generator) {
        Objects.requireNonNull(generator, "generator");
        return workers.submit(() -> {
            ChunkSnapshot snapshot = generator.call();
            return mesher.mesh(snapshot, lighting.calculate(snapshot), pool);
        });
    }

    public int pendingChunks() { return workers.pendingTasks(); }

    /** Drains work. The caller must close delivered meshes before releasing the external pool. */
    @Override public void close() { workers.close(); }
}
