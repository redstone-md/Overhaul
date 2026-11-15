package dev.overhaul.minecraft.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The layers that blend against the world, drawn live outside the cached HUD. */
@Mixin(Gui.class)
public interface HudCacheGuiInvoker {
    @Invoker("renderCameraOverlays")
    void overhaul$renderCameraOverlays(GuiGraphics graphics, DeltaTracker delta);

    @Invoker("renderCrosshair")
    void overhaul$renderCrosshair(GuiGraphics graphics, DeltaTracker delta);
}
