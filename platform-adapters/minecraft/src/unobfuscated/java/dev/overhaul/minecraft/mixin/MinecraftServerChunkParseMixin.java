package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkParseAccess;
import dev.overhaul.minecraft.world.ChunkParseExecutor;
import dev.overhaul.minecraft.world.ChunkLightAccess;
import dev.overhaul.minecraft.world.ChunkLightingExecutor;
import dev.overhaul.minecraft.world.ChunkGenerationAccess;
import dev.overhaul.minecraft.world.ChunkGenerationExecutor;
import dev.overhaul.minecraft.world.GenerationStateAccess;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lazy per-server ownership avoids a pool per dimension or static server retention. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerChunkParseMixin implements ChunkParseAccess, ChunkLightAccess, ChunkGenerationAccess {
    @Unique private ChunkParseExecutor overhaul$chunkParser;
    @Unique private boolean overhaul$chunkParsingClosed;
    @Unique private ChunkLightingExecutor overhaul$chunkLighting;
    @Unique private boolean overhaul$chunkLightingClosed;
    @Unique private ChunkGenerationExecutor overhaul$chunkGeneration;
    @Unique private boolean overhaul$chunkGenerationClosed;

    @Override public synchronized Executor overhaul$chunkParseExecutor(Executor vanillaParseExecutor) {
        if (overhaul$chunkParsingClosed) throw new RejectedExecutionException("server chunk parsing is closed");
        if (overhaul$chunkParser == null) overhaul$chunkParser = new ChunkParseExecutor(vanillaParseExecutor);
        return overhaul$chunkParser;
    }

    @Override public synchronized <E> ChunkLightingExecutor.Strand<E> overhaul$createLightStrand(
            Executor vanillaExecutor, String name, BiFunction<Executor, String, E> factory, Consumer<E> closer) {
        if (overhaul$chunkLightingClosed) throw new RejectedExecutionException("server chunk lighting is closed");
        if (overhaul$chunkLighting == null) overhaul$chunkLighting = new ChunkLightingExecutor(vanillaExecutor);
        return overhaul$chunkLighting.createStrand(vanillaExecutor, name, factory, closer);
    }

    @Override public synchronized ChunkGenerationExecutor.World overhaul$createGenerationWorld(
            GenerationStateAccess state, Runnable stopGeneration) {
        if (overhaul$chunkGenerationClosed) throw new RejectedExecutionException("server chunk generation is closed");
        if (overhaul$chunkGeneration == null) overhaul$chunkGeneration = new ChunkGenerationExecutor(Util.backgroundExecutor());
        return overhaul$chunkGeneration.createWorld(state, stopGeneration);
    }

    @Inject(method = "stopServer()V", at = @At("RETURN"), require = 1)
    private void overhaul$stopped(CallbackInfo callback) { overhaul$closeChunkWorkers(); }

    @Inject(method = "stopServer()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;close()V"), require = 1)
    private void overhaul$closingLevels(CallbackInfo callback) { overhaul$closeChunkParser(); }

    // runServer invokes the virtual exit hook on both normal and exceptional stop
    // paths. Inject at its call sites: subclasses may override onServerExit itself.
    @Inject(method = "runServer()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;onServerExit()V"), require = 1)
    private void overhaul$exiting(CallbackInfo callback) { overhaul$closeChunkWorkers(); }

    @Unique private void overhaul$closeChunkWorkers() {
        Throwable failure = null;
        try { overhaul$closeChunkParser(); }
        catch (RuntimeException | Error problem) { failure = problem; }
        try { overhaul$closeChunkGeneration(); }
        catch (RuntimeException | Error problem) { failure = overhaul$accumulate(failure, problem); }
        try { overhaul$closeChunkLighting(); }
        catch (RuntimeException | Error problem) { failure = overhaul$accumulate(failure, problem); }
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
    }

    @Unique private static Throwable overhaul$accumulate(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    @Unique private void overhaul$closeChunkGeneration() {
        ChunkGenerationExecutor generation;
        synchronized (this) {
            overhaul$chunkGenerationClosed = true;
            generation = overhaul$chunkGeneration;
        }
        if (generation != null) generation.close();
    }

    @Unique private void overhaul$closeChunkLighting() {
        ChunkLightingExecutor lighting;
        synchronized (this) {
            overhaul$chunkLightingClosed = true;
            lighting = overhaul$chunkLighting;
        }
        if (lighting != null) lighting.close();
    }

    @Unique private void overhaul$closeChunkParser() {
        ChunkParseExecutor parser;
        synchronized (this) {
            overhaul$chunkParsingClosed = true;
            parser = overhaul$chunkParser;
        }
        if (parser != null) parser.close();
    }
}
