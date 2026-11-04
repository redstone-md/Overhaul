package dev.overhaul.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.hud.HudFrameCache;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Wraps the HUD call outside ImmediatelyFast's batching, whose deferred draws must land in the cache buffer. */
@Mixin(value = GameRenderer.class, priority = 1100)
abstract class HudCacheGameRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
    private void overhaul$cacheHud(Gui gui, GuiGraphics graphics, DeltaTracker delta, Operation<Void> original) {
        HudFrameCache.render(gui, graphics, delta, () -> original.call(gui, graphics, delta));
    }
}
