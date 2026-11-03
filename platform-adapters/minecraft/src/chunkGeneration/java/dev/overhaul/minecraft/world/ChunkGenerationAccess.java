package dev.overhaul.minecraft.world;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Server lifecycle owns the workers; ChunkMap owns its generation dependency scope. */
public interface ChunkGenerationAccess {
    ChunkGenerationExecutor.World overhaul$createGenerationWorld(GenerationStateAccess state, Runnable stopGeneration);

    default ChunkGenerationExecutor.World overhaul$createGenerationWorld(GenerationStateAccess state,
            Runnable stopGeneration, Consumer<BooleanSupplier> awaiter) {
        throw new UnsupportedOperationException("this generation owner has no event-loop drain");
    }
}
