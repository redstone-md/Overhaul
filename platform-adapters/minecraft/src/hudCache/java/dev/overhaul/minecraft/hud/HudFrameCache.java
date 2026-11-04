package dev.overhaul.minecraft.hud;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.mixin.HudCacheGuiAccessor;
import dev.overhaul.minecraft.mixin.HudCacheGuiLayerManagerAccessor;
import dev.overhaul.minecraft.mixin.HudCacheMinecraftAccessor;
import java.util.Set;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/**
 * Redraws the HUD at a fixed rate into its own framebuffer and composites that image every frame.
 *
 * <p>Layers that blend against the world (vignette, inverted crosshair) cannot live in a transparent buffer,
 * so they are drawn directly each frame. They are the first layers, so drawing them before the composite keeps the order.
 */
public final class HudFrameCache {
    /** Read each frame so a running client can be compared with and without the cache. */
    public static volatile boolean enabled = !"false".equals(System.getProperty("overhaul.hudCache"));

    private static final long REFRESH_NANOS = 1_000_000_000L / Math.max(1L, Long.getLong("overhaul.hudCache.hz", 30L));
    private static final Set<ResourceLocation> LIVE_LAYERS = Set.of(VanillaGuiLayers.CAMERA_OVERLAYS, VanillaGuiLayers.CROSSHAIR);

    private static boolean listening;
    private static boolean cachedPass;
    private static TextureTarget target;
    private static long lastRefresh;

    private HudFrameCache() {
    }

    public static void render(Gui gui, GuiGraphics graphics, DeltaTracker delta, Runnable original) {
        Minecraft minecraft = Minecraft.getInstance();
        // The debug overlay is read as a live measurement, so it must not show stale numbers.
        if (!enabled || minecraft.getDebugOverlay().showDebugScreen()) {
            original.run();
            return;
        }
        if (!listening) {
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderGuiLayerEvent.Pre.class, HudFrameCache::skipLiveLayer);
            listening = true;
        }
        graphics.flush();
        renderLiveLayers(gui, graphics, delta);
        RenderTarget main = minecraft.getMainRenderTarget();
        boolean sizeChanged = target == null || target.width != main.width || target.height != main.height;
        long now = System.nanoTime();
        if (sizeChanged || now - lastRefresh >= REFRESH_NANOS) {
            refresh(minecraft, main, graphics, sizeChanged, original);
            lastRefresh = now;
        }
        composite(minecraft);
    }

    private static void skipLiveLayer(RenderGuiLayerEvent.Pre event) {
        if (cachedPass && LIVE_LAYERS.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    private static void renderLiveLayers(Gui gui, GuiGraphics graphics, DeltaTracker delta) {
        GuiLayerManager manager = ((HudCacheGuiAccessor) gui).overhaul$layerManager();
        PoseStack pose = graphics.pose();
        RenderSystem.enableDepthTest();
        pose.pushPose();
        for (GuiLayerManager.NamedLayer layer : ((HudCacheGuiLayerManagerAccessor) manager).overhaul$layers()) {
            if (!LIVE_LAYERS.contains(layer.name())) {
                continue;
            }
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

    private static void refresh(Minecraft minecraft, RenderTarget main, GuiGraphics graphics, boolean sizeChanged, Runnable original) {
        if (target == null) {
            target = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        } else if (sizeChanged) {
            target.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        if (main.isStencilEnabled() && !target.isStencilEnabled()) {
            target.enableStencil();
        }
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        // Mods rebind the main target after drawing to their own buffers, so that name must point here during the pass.
        HudCacheMinecraftAccessor targets = (HudCacheMinecraftAccessor) minecraft;
        targets.overhaul$setMainRenderTarget(target);
        cachedPass = true;
        try {
            original.run();
            graphics.flush();
        } finally {
            cachedPass = false;
            targets.overhaul$setMainRenderTarget(main);
            main.bindWrite(true);
        }
    }

    private static void composite(Minecraft minecraft) {
        float width = minecraft.getWindow().getGuiScaledWidth();
        float height = minecraft.getWindow().getGuiScaledHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        // The cached pass blends onto transparent black, which leaves premultiplied color in the buffer.
        RenderSystem.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        quad.addVertex(0.0F, height, 0.0F).setUv(0.0F, 0.0F);
        quad.addVertex(width, height, 0.0F).setUv(1.0F, 0.0F);
        quad.addVertex(width, 0.0F, 0.0F).setUv(1.0F, 1.0F);
        quad.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 1.0F);
        BufferUploader.drawWithShader(quad.buildOrThrow());
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
