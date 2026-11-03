package dev.overhaul.minecraft.world;

import java.util.concurrent.Executor;

/** Server-owned parsing scope; callers receive scheduling access only. */
public interface ChunkParseAccess {
    Executor overhaul$chunkParseExecutor(Executor vanillaParseExecutor);
}
