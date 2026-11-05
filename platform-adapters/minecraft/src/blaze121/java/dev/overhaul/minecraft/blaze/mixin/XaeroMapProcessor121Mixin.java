package dev.overhaul.minecraft.blaze.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Xaero's world map writes chunks and uploads tiles from every rendered frame; past 60 a second only the map's own pace gains. */
@Pseudo
@Mixin(targets = "xaero.map.MapProcessor", remap = false)
abstract class XaeroMapProcessor121Mixin {
    @Unique private static final long PERIOD_NANOS = 16_000_000L;
    @Unique private long overhaul$lastProcess;

    @Inject(method = "onRenderProcess", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$limitRate(CallbackInfo callback) {
        long now = System.nanoTime();
        if (now - overhaul$lastProcess < PERIOD_NANOS) callback.cancel();
        else overhaul$lastProcess = now;
    }
}
