package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkParseAccess;
import dev.overhaul.minecraft.world.ChunkParseExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lazy per-server ownership avoids a pool per dimension or static server retention. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerChunkParseMixin implements ChunkParseAccess {
    @Unique private ChunkParseExecutor overhaul$chunkParser;
    @Unique private boolean overhaul$chunkParsingClosed;

    @Override public synchronized Executor overhaul$chunkParseExecutor(Executor vanillaParseExecutor) {
        if (overhaul$chunkParsingClosed) throw new RejectedExecutionException("server chunk parsing is closed");
        if (overhaul$chunkParser == null) overhaul$chunkParser = new ChunkParseExecutor(vanillaParseExecutor);
        return overhaul$chunkParser;
    }

    @Inject(method = "stopServer()V", at = @At("RETURN"), require = 1)
    private void overhaul$stopped(CallbackInfo callback) { overhaul$closeChunkParser(); }

    @Inject(method = "stopServer()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;close()V"), require = 1)
    private void overhaul$closingLevels(CallbackInfo callback) { overhaul$closeChunkParser(); }

    // runServer invokes the virtual exit hook on both normal and exceptional stop
    // paths. Inject at its call sites: subclasses may override onServerExit itself.
    @Inject(method = "runServer()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;onServerExit()V"), require = 1)
    private void overhaul$exiting(CallbackInfo callback) { overhaul$closeChunkParser(); }

    @Unique private void overhaul$closeChunkParser() {
        ChunkParseExecutor parser;
        synchronized (this) {
            overhaul$chunkParsingClosed = true;
            parser = overhaul$chunkParser;
        }
        if (parser != null) parser.close();
    }
}
