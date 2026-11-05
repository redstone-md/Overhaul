package dev.overhaul.minecraft.blaze.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Casts a ray through the world for the crosshair's place on every frame, and the crosshair is drawn with the HUD at a lower rate. */
@Pseudo
@Mixin(targets = "com.github.exopandora.shouldersurfing.client.renderer.CrosshairRenderer", remap = false)
abstract class ShoulderCrosshair121Mixin {
    @Unique private static final long PERIOD_NANOS = 16_000_000L;
    @Unique private long overhaul$lastTick;

    @Inject(method = "renderTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$limitRate(CallbackInfo callback) {
        long now = System.nanoTime();
        if (now - overhaul$lastTick < PERIOD_NANOS) callback.cancel();
        else overhaul$lastTick = now;
    }
}
