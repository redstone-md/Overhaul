package dev.overhaul.minecraft.terrain;

import net.minecraft.client.renderer.chunk.SectionCompiler;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Dispatcher-local diagnostics and compiler replacement retirement. */
public interface TerrainDispatcherAccess {
    SectionCompiler overhaul$sectionCompiler();
    CompletableFuture<Void> overhaul$afterCurrentTasks(Runnable release);
    Map<String,Long> overhaul$workerStatistics();
}
