package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkGenerationExecutor;
import dev.overhaul.minecraft.world.GenerationStateAccess;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** RandomState is created per ChunkMap; shared generator instances need no mutable binding. */
@Mixin(RandomState.class)
public abstract class RandomStateGenerationMixin implements GenerationStateAccess {
    @Unique private volatile ChunkGenerationExecutor.World overhaul$generation;
    @Unique private volatile boolean overhaul$generationRetired;

    @Override public synchronized void overhaul$bindGeneration(ChunkGenerationExecutor.World world) {
        Objects.requireNonNull(world, "world");
        if (overhaul$generation != null && overhaul$generation != world) {
            throw new IllegalStateException("RandomState is already bound to a generation world");
        }
        overhaul$generationRetired = false;
        overhaul$generation = world;
    }

    @Override public synchronized void overhaul$unbindGeneration(ChunkGenerationExecutor.World world) {
        if (overhaul$generation == world) {
            overhaul$generationRetired = true;
            overhaul$generation = null;
        }
    }

    @Override public Executor overhaul$generationExecutor(String stage, Executor vanillaExecutor) {
        ChunkGenerationExecutor.World world = overhaul$generation;
        if (world == null && overhaul$generationRetired) {
            throw new RejectedExecutionException("world chunk generation is retired");
        }
        return world == null ? vanillaExecutor : world.executor(stage, vanillaExecutor);
    }

    @Override public <T> CompletableFuture<T> overhaul$generationInvocation(Supplier<CompletableFuture<T>> operation) {
        ChunkGenerationExecutor.World world = overhaul$generation;
        if (world == null && overhaul$generationRetired) {
            CompletableFuture<T> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new RejectedExecutionException("world chunk generation is retired"));
            return rejected;
        }
        return world == null ? operation.get() : world.invoke(operation);
    }
}
