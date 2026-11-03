package dev.overhaul.minecraft.world;

import java.util.concurrent.Executor;

/** Server owns the shared workers; each dimension owns its original serial strand. */
public interface ChunkLightAccess {
    ChunkLightingExecutor.Strand overhaul$createLightStrand(Executor vanillaExecutor, String name);
}
