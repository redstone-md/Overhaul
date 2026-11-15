package dev.overhaul.minecraft.hud;

import com.mojang.blaze3d.platform.Window;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.GuiOverlayManager;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Forge's overlays that blend against the world. The camera overlays come first; the crosshair follows the hotbar,
 * which it never overlaps, so drawing it before the cached image changes nothing on screen.
 */
public final class HudLiveLayers {
    private static final Set<ResourceLocation> LIVE = Set.of(VanillaGuiOverlay.VIGNETTE.id(), VanillaGuiOverlay.SPYGLASS.id(),
            VanillaGuiOverlay.HELMET.id(), VanillaGuiOverlay.FROSTBITE.id(), VanillaGuiOverlay.PORTAL.id(), VanillaGuiOverlay.CROSSHAIR.id());

    private static List<NamedGuiOverlay> source;
    private static NamedGuiOverlay[] live;
    private static boolean listening;

    private HudLiveLayers() {
    }

    public static void render(GuiGraphics graphics, float partialTick, Runnable original) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.gui instanceof ForgeGui gui) || !HudFrameCache.applies(minecraft.options.renderDebug)) {
            original.run();
            return;
        }
        if (!listening) {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderGuiOverlayEvent.Pre.class, HudLiveLayers::skipInCache);
            listening = true;
        }
        HudFrameCache.render(graphics, () -> draw(minecraft, gui, graphics, partialTick), original);
    }

    private static void skipInCache(RenderGuiOverlayEvent.Pre event) {
        if (HudFrameCache.cachedPass() && LIVE.contains(event.getOverlay().id())) {
            event.setCanceled(true);
        }
    }

    private static NamedGuiOverlay[] live() {
        List<NamedGuiOverlay> overlays = GuiOverlayManager.getOverlays();
        if (overlays != source) {
            live = overlays.stream().filter(overlay -> LIVE.contains(overlay.id())).toArray(NamedGuiOverlay[]::new);
            source = overlays;
        }
        return live;
    }

    private static void draw(Minecraft minecraft, ForgeGui gui, GuiGraphics graphics, float partialTick) {
        Window window = minecraft.getWindow();
        int width = window.getGuiScaledWidth();
        int height = window.getGuiScaledHeight();
        float frameTime = minecraft.getFrameTime();
        for (NamedGuiOverlay overlay : live()) {
            if (!MinecraftForge.EVENT_BUS.post(new RenderGuiOverlayEvent.Pre(window, graphics, frameTime, overlay))) {
                overlay.overlay().render(gui, graphics, partialTick, width, height);
                MinecraftForge.EVENT_BUS.post(new RenderGuiOverlayEvent.Post(window, graphics, frameTime, overlay));
            }
        }
    }
}
