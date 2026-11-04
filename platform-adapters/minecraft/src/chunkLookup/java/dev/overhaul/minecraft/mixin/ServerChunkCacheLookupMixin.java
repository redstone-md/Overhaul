package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.world.ChunkLookupTable;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Widens the game's chunk cache for the same lifetime: filled where it is filled and
 * emptied where it is emptied, so a hit returns what the game's own cache could have.
 * Only final-form chunks are kept; a null or a proto chunk could go stale before the next clear.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheLookupMixin {
    @Unique private final ChunkLookupTable overhaul$lookup = new ChunkLookupTable();

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"),
            cancellable = true,
            require = 1)
    private void overhaul$lookupWider(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> callback) {
        ChunkAccess chunk = overhaul$lookup.get(ChunkLookupTable.position(x, z), status);
        if (chunk != null) callback.setReturnValue(chunk);
    }

    @Inject(method = "storeInCache", at = @At("HEAD"), require = 1)
    private void overhaul$remember(long position, ChunkAccess chunk, ChunkStatus status, CallbackInfo callback) {
        if (chunk instanceof LevelChunk || chunk instanceof ImposterProtoChunk) overhaul$lookup.put(position, status, chunk);
    }

    @Inject(method = "clearCache", at = @At("HEAD"), require = 1)
    private void overhaul$forget(CallbackInfo callback) {
        overhaul$lookup.clear();
    }
}
