package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Each cloud tile moves itself and checks its colour on every frame, a tenth of a block at most between 60 Hz steps. The
 * choice is made per frame so tiles never part, and a skipped update's time is kept by the handler's own clock.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler", remap = false)
abstract class DhClouds121Mixin {
    @Unique private static final long PERIOD_NANOS = 16_000_000L;
    @Unique private static long overhaul$frame, overhaul$updatedAt;
    @Unique private static boolean overhaul$updating;

    @Inject(method = "preRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$limitRate(CallbackInfo callback) {
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null) return;
        long frame = renderer.frame();
        if (frame != overhaul$frame) {
            long now = System.nanoTime();
            overhaul$frame = frame;
            overhaul$updating = now - overhaul$updatedAt >= PERIOD_NANOS;
            if (overhaul$updating) overhaul$updatedAt = now;
        }
        if (!overhaul$updating) callback.cancel();
    }
}
