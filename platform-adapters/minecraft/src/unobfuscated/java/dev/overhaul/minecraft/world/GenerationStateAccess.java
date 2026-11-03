package dev.overhaul.minecraft.world;

import java.util.concurrent.Executor;

/** Per-world RandomState association; no generator-instance ownership or global lookup. */
public interface GenerationStateAccess {
    void overhaul$bindGeneration(ChunkGenerationExecutor.World world);
    void overhaul$unbindGeneration(ChunkGenerationExecutor.World world);
    Executor overhaul$generationExecutor(String stage, Executor vanillaExecutor);
}
