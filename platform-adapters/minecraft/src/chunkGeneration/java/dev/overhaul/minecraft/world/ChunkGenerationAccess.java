package dev.overhaul.minecraft.world;

/** Server lifecycle owns the workers; ChunkMap owns its generation dependency scope. */
public interface ChunkGenerationAccess {
    ChunkGenerationExecutor.World overhaul$createGenerationWorld(GenerationStateAccess state, Runnable stopGeneration);
}
