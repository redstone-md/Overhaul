package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the performance overlay over the rest of the HUD. */
@Mixin(Gui.class)
abstract class GuiOverlay120Mixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void overhaul$overlay(GuiGraphics graphics, float partialTick, CallbackInfo info) {
        BlazeOverlay.get().render(graphics);
    }
}
