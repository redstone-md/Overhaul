package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkGenerationAccess;
import dev.overhaul.minecraft.world.ChunkGenerationExecutor;
import dev.overhaul.minecraft.world.GenerationStateAccess;
import dev.overhaul.minecraft.world.ChunkLightAccess;
import dev.overhaul.minecraft.world.ChunkLightingExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A single lazy worker owner per real server, shared by independently drainable worlds. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMappedGenerationMixin implements ChunkGenerationAccess, ChunkLightAccess {
    @Unique private ChunkGenerationExecutor overhaul$chunkGeneration;
    @Unique private boolean overhaul$generationClosed;
    @Unique private ChunkLightingExecutor overhaul$chunkLighting;
    @Unique private boolean overhaul$lightingClosed;

    @Override public ChunkGenerationExecutor.World overhaul$createGenerationWorld(
            GenerationStateAccess state, Runnable stopGeneration) {
        return overhaul$createGenerationWorld(state, stopGeneration, null);
    }

    @Override public synchronized ChunkGenerationExecutor.World overhaul$createGenerationWorld(
            GenerationStateAccess state, Runnable stopGeneration, Consumer<BooleanSupplier> awaiter) {
        if (overhaul$generationClosed) throw new RejectedExecutionException("server chunk generation is closed");
        if (overhaul$chunkGeneration == null) {
            overhaul$chunkGeneration = new ChunkGenerationExecutor(Util.backgroundExecutor());
        }
        return overhaul$chunkGeneration.createWorld(state, stopGeneration, awaiter);
    }

    @Override public synchronized <E> ChunkLightingExecutor.Strand<E> overhaul$createLightStrand(
            Executor vanillaExecutor, String name, BiFunction<Executor, String, E> factory, Consumer<E> closer) {
        if (overhaul$lightingClosed) throw new RejectedExecutionException("server chunk lighting is closed");
        if (overhaul$chunkLighting == null) overhaul$chunkLighting = new ChunkLightingExecutor(vanillaExecutor);
        return overhaul$chunkLighting.createStrand(vanillaExecutor, name, factory, closer);
    }

    @Inject(method = "stopServer()V", at = @At("RETURN"), require = 1)
    private void overhaul$stopped(CallbackInfo callback) { overhaul$closeWorkers(); }

    @Inject(method = "runServer()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;onServerExit()V"), require = 1)
    private void overhaul$exiting(CallbackInfo callback) { overhaul$closeWorkers(); }

    @Unique private void overhaul$closeWorkers() {
        Throwable failure = null;
        try { overhaul$closeGeneration(); }
        catch (RuntimeException | Error problem) { failure = problem; }
        try { overhaul$closeLighting(); }
        catch (RuntimeException | Error problem) {
            if (failure == null) failure = problem;
            else failure.addSuppressed(problem);
        }
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    @Unique private void overhaul$closeLighting() {
        ChunkLightingExecutor lighting;
        synchronized (this) {
            overhaul$lightingClosed = true;
            lighting = overhaul$chunkLighting;
        }
        if (lighting != null) lighting.close();
    }

    @Unique private void overhaul$closeGeneration() {
        ChunkGenerationExecutor generation;
        synchronized (this) {
            overhaul$generationClosed = true;
            generation = overhaul$chunkGeneration;
        }
        if (generation != null) generation.close();
    }
}
