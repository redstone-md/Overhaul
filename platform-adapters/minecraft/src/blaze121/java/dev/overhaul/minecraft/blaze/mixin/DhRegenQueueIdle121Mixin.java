package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons' regeneration thread keeps asking its database for positions to regenerate for as long as each
 * pass reports success, and a pass whose positions are all already generating still reports success. It then
 * re-runs the same query back to back on one core. A pass that neither queued a retrieval nor cleared a position
 * now ends the loop, and the propagator's own 250 ms tick starts the next one.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2", remap = false)
abstract class DhRegenQueueIdle121Mixin {
    @Unique private boolean overhaul$progressed;

    @Inject(method = "queueRegeneration", at = @At("HEAD"), require = 0)
    private void overhaul$begin(CallbackInfoReturnable<Boolean> callback) {
        overhaul$progressed = false;
    }

    @Inject(method = "tryQueueWorldGenTask", require = 0, at = {
            @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/file/fullDatafile/GeneratedFullDataSourceProvider;queuePositionForRetrieval(Ljava/lang/Long;)Ljava/util/concurrent/CompletableFuture;"),
            @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/sql/repo/FullDataSourceV2Repo;setRegenerate(JZ)V")})
    private void overhaul$progress(CallbackInfoReturnable<Boolean> callback) {
        overhaul$progressed = true;
    }

    @ModifyReturnValue(method = "queueRegeneration", at = @At("RETURN"), require = 0)
    private boolean overhaul$onlyWhenProgressed(boolean started) {
        return started && overhaul$progressed;
    }
}
