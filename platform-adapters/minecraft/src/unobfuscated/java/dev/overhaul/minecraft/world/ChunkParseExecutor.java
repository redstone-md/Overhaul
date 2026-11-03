package dev.overhaul.minecraft.world;

import dev.overhaul.core.concurrent.BoundedWorkerExecutor;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * One bounded parsing pool per server, shared across dimensions. Saturation uses
 * Minecraft's original asynchronous parse executor, never the submitting thread.
 * Only the parseChunk future's runnable is redirected; POI and world publication
 * remain in ChunkMap's original continuation graph.
 */
public final class ChunkParseExecutor implements Executor, AutoCloseable {
    private final BoundedWorkerExecutor workers;

    public ChunkParseExecutor(Executor overflow) {
        int workerCount = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        workers = new BoundedWorkerExecutor(workerCount, 128, "overhaul-chunk-parse", overflow);
    }

    @Override public void execute(Runnable command) { workers.execute(command); }
    public Map<String, Long> snapshot() { return workers.snapshot(); }

    /** Drains parsing, including overflow, without waiting for main-thread publication. */
    @Override public void close() { workers.close(); }
}
