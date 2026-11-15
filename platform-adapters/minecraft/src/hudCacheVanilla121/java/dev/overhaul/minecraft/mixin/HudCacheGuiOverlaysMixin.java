package dev.overhaul.minecraft.mixin;

import dev.overhaul.minecraft.hud.HudFrameCache;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the layers that blend against the world out of the cached HUD; the adapter draws them live every frame. */
@Mixin(Gui.class)
abstract class HudCacheGuiOverlaysMixin {
    @Inject(method = {"renderCameraOverlays", "renderCrosshair"}, at = @At("HEAD"), cancellable = true)
    private void overhaul$skipInCache(CallbackInfo callback) {
        if (HudFrameCache.cachedPass()) callback.cancel();
    }
}
