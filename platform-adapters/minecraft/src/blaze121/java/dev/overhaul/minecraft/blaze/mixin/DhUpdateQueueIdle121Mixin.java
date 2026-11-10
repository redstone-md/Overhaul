package dev.overhaul.minecraft.blaze.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons' chunk update thread pops the closest queued chunk and puts it straight back while it is younger
 * than the 250 ms debounce, or while the LOD builder rejects it, then loops again without sleeping. A pass that
 * neither pre-updated nor dispatched a chunk now parks the thread for a few milliseconds instead of spinning a core.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager", remap = false)
abstract class DhUpdateQueueIdle121Mixin {
    @Unique private static final long OVERHAUL$IDLE_MS = 5L;
    @Unique private boolean overhaul$worked;

    @Inject(method = "processQueuedChunkPreUpdate", require = 0,
            at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/chunk/IChunkWrapper;createDhHeightMaps()V"))
    private void overhaul$preUpdated(CallbackInfo callback) {
        overhaul$worked = true;
    }

    @Inject(method = "tryDispatchReadyChunkUpdate", require = 0,
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/AbstractExecutorService;execute(Ljava/lang/Runnable;)V", shift = At.Shift.AFTER))
    private void overhaul$dispatched(CallbackInfo callback) {
        overhaul$worked = true;
    }

    @Inject(method = "processQueue", at = @At("TAIL"), require = 0)
    private void overhaul$idle(CallbackInfo callback) {
        boolean worked = overhaul$worked;
        overhaul$worked = false;
        if (worked) return;
        try {
            Thread.sleep(OVERHAUL$IDLE_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
