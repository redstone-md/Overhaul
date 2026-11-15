package dev.overhaul.minecraft.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.overhaul.minecraft.mixin.HudCacheGuiInvoker;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

/**
 * Vanilla's first two HUD layers, as Fabric and Forge draw them on 1.21.1: the camera overlays and the crosshair blend
 * against the world, so they draw here every frame, at the depth their layers take, and are skipped in the cached pass.
 */
public final class HudLiveLayers {
    private HudLiveLayers() {
    }

    public static void render(Gui gui, GuiGraphics graphics, DeltaTracker delta, Runnable original) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!HudFrameCache.applies(minecraft.getDebugOverlay().showDebugScreen())) {
            original.run();
            return;
        }
        HudFrameCache.render(graphics, () -> draw(minecraft, (HudCacheGuiInvoker) gui, graphics, delta), original);
    }

    private static void draw(Minecraft minecraft, HudCacheGuiInvoker gui, GuiGraphics graphics, DeltaTracker delta) {
        if (minecraft.options.hideGui) return;
        PoseStack pose = graphics.pose();
        RenderSystem.enableDepthTest();
        pose.pushPose();
        gui.overhaul$renderCameraOverlays(graphics, delta);
        pose.translate(0.0F, 0.0F, LayeredDraw.Z_SEPARATION);
        gui.overhaul$renderCrosshair(graphics, delta);
        pose.popPose();
        graphics.flush();
        RenderSystem.disableDepthTest();
    }
}
