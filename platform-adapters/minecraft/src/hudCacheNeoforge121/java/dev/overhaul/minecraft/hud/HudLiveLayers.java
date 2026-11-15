package dev.overhaul.minecraft.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.overhaul.minecraft.mixin.HudCacheGuiAccessor;
import dev.overhaul.minecraft.mixin.HudCacheGuiLayerManagerAccessor;
import java.util.List;
import java.util.Set;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge's GUI layers that blend against the world; they are the first layers, so drawing them first keeps the order. */
public final class HudLiveLayers {
    private static final Set<ResourceLocation> LIVE = Set.of(VanillaGuiLayers.CAMERA_OVERLAYS, VanillaGuiLayers.CROSSHAIR);

    private static List<GuiLayerManager.NamedLayer> source;
    private static int sourceSize;
    private static GuiLayerManager.NamedLayer[] live;
    private static boolean listening;

    private HudLiveLayers() {
    }

    public static void render(Gui gui, GuiGraphics graphics, DeltaTracker delta, Runnable original) {
        if (!HudFrameCache.applies(Minecraft.getInstance().getDebugOverlay().showDebugScreen())) {
            original.run();
            return;
        }
        if (!listening) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderGuiLayerEvent.Pre.class, HudLiveLayers::skipInCache);
            listening = true;
        }
        HudFrameCache.render(graphics, () -> draw(gui, graphics, delta), original);
    }

    private static void skipInCache(RenderGuiLayerEvent.Pre event) {
        if (HudFrameCache.cachedPass() && LIVE.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    private static GuiLayerManager.NamedLayer[] live(List<GuiLayerManager.NamedLayer> layers) {
        if (layers != source || layers.size() != sourceSize) {
            live = layers.stream().filter(layer -> LIVE.contains(layer.name())).toArray(GuiLayerManager.NamedLayer[]::new);
            source = layers;
            sourceSize = layers.size();
        }
        return live;
    }

    private static void draw(Gui gui, GuiGraphics graphics, DeltaTracker delta) {
        GuiLayerManager manager = ((HudCacheGuiAccessor) gui).overhaul$layerManager();
        PoseStack pose = graphics.pose();
        RenderSystem.enableDepthTest();
        pose.pushPose();
        for (GuiLayerManager.NamedLayer layer : live(((HudCacheGuiLayerManagerAccessor) manager).overhaul$layers())) {
            if (!NeoForge.EVENT_BUS.post(new RenderGuiLayerEvent.Pre(graphics, delta, layer.name(), layer.layer())).isCanceled()) {
                layer.layer().render(graphics, delta);
                NeoForge.EVENT_BUS.post(new RenderGuiLayerEvent.Post(graphics, delta, layer.name(), layer.layer()));
            }
            pose.translate(0.0F, 0.0F, GuiLayerManager.Z_SEPARATION);
        }
        pose.popPose();
        graphics.flush();
        RenderSystem.disableDepthTest();
    }
}
