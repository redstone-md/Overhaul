package dev.overhaul.minecraft.world;

import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Server owns the shared workers; each dimension owns its original serial strand. */
public interface ChunkLightAccess {
    <E> ChunkLightingExecutor.Strand<E> overhaul$createLightStrand(Executor vanillaExecutor, String name,
            BiFunction<Executor, String, E> factory, Consumer<E> closer);
}
