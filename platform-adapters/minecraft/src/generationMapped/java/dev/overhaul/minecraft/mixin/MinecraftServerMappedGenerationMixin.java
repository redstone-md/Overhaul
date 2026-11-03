package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkGenerationAccess;
import dev.overhaul.minecraft.world.ChunkGenerationExecutor;
import dev.overhaul.minecraft.world.GenerationStateAccess;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A single lazy worker owner per real server, shared by independently drainable worlds. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMappedGenerationMixin implements ChunkGenerationAccess {
    @Unique private ChunkGenerationExecutor overhaul$chunkGeneration;
    @Unique private boolean overhaul$generationClosed;

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

    @Inject(method = "stopServer()V", at = @At("RETURN"), require = 1)
    private void overhaul$stopped(CallbackInfo callback) { overhaul$closeGeneration(); }

    @Inject(method = "runServer()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;onServerExit()V"), require = 1)
    private void overhaul$exiting(CallbackInfo callback) { overhaul$closeGeneration(); }

    @Unique private void overhaul$closeGeneration() {
        ChunkGenerationExecutor generation;
        synchronized (this) {
            overhaul$generationClosed = true;
            generation = overhaul$chunkGeneration;
        }
        if (generation != null) generation.close();
    }
}
