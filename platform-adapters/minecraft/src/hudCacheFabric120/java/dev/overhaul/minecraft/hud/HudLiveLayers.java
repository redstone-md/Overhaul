package dev.overhaul.minecraft.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.mixin.HudCacheGuiInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;

/**
 * Vanilla 1.20.1 draws the HUD in one method, so the overlays that blend against the world are drawn here in its
 * order and skipped in the cached pass. The crosshair follows the hotbar, which it never overlaps, so drawing it
 * before the cached image changes nothing on screen.
 */
public final class HudLiveLayers {
    private static final ResourceLocation PUMPKIN_BLUR = new ResourceLocation("textures/misc/pumpkinblur.png");
    private static final ResourceLocation POWDER_SNOW_OUTLINE = new ResourceLocation("textures/misc/powder_snow_outline.png");

    private HudLiveLayers() {
    }

    public static void render(GuiGraphics graphics, float partialTick, Runnable original) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !HudFrameCache.applies(minecraft.options.renderDebug)) {
            original.run();
            return;
        }
        HudCacheGuiInvoker gui = (HudCacheGuiInvoker) minecraft.gui;
        HudFrameCache.render(graphics, () -> draw(minecraft, player, gui, graphics, partialTick), () -> {
            // The cached pass eases the spyglass zoom as well; only the live pass may, once a frame.
            float scope = gui.overhaul$scopeScale();
            original.run();
            gui.overhaul$setScopeScale(scope);
        });
    }

    private static void draw(Minecraft minecraft, LocalPlayer player, HudCacheGuiInvoker gui, GuiGraphics graphics, float partialTick) {
        RenderSystem.enableBlend();
        if (Minecraft.useFancyGraphics()) gui.overhaul$renderVignette(graphics, minecraft.getCameraEntity());
        float scope = Mth.lerp(0.5F * minecraft.getDeltaFrameTime(), gui.overhaul$scopeScale(), 1.125F);
        gui.overhaul$setScopeScale(scope);
        if (minecraft.options.getCameraType().isFirstPerson()) {
            if (player.isScoping()) {
                gui.overhaul$renderSpyglassOverlay(graphics, scope);
            } else {
                gui.overhaul$setScopeScale(0.5F);
                if (player.getInventory().getArmor(3).is(Blocks.CARVED_PUMPKIN.asItem())) gui.overhaul$renderTextureOverlay(graphics, PUMPKIN_BLUR, 1.0F);
            }
        }
        if (player.getTicksFrozen() > 0) gui.overhaul$renderTextureOverlay(graphics, POWDER_SNOW_OUTLINE, player.getPercentFrozen());
        float spin = Mth.lerp(partialTick, player.oSpinningEffectIntensity, player.spinningEffectIntensity);
        if (spin > 0.0F && !player.hasEffect(MobEffects.CONFUSION)) gui.overhaul$renderPortalOverlay(graphics, spin);
        if (!minecraft.options.hideGui) {
            RenderSystem.enableBlend();
            gui.overhaul$renderCrosshair(graphics);
        }
    }
}
