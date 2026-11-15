package dev.overhaul.minecraft.hud;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.mixin.HudCacheMinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;

/**
 * Redraws the HUD at a fixed rate into its own framebuffer and composites that image every frame.
 *
 * <p>Layers that blend against the world (vignette, inverted crosshair) cannot live in a transparent buffer,
 * so each loader's adapter draws them directly every frame and keeps them out of the cached pass.
 */
public final class HudFrameCache {
    /** Read each frame so a running client can be compared with and without the cache. */
    public static volatile boolean enabled = !"false".equals(System.getProperty("overhaul.hudCache"));

    private static final long REFRESH_NANOS = 1_000_000_000L / Math.max(1L, Long.getLong("overhaul.hudCache.hz", 30L));
    private static final boolean CACHES_DEBUG = Boolean.getBoolean("overhaul.hudCache.debug");

    private static boolean cachedPass;
    private static TextureTarget target;
    private static long lastRefresh;

    private HudFrameCache() {
    }

    /** The debug overlay is read as a live measurement, so it stays live unless its text refreshes slower than the cache. */
    public static boolean applies(boolean debugShown) {
        return enabled && (CACHES_DEBUG || !debugShown);
    }

    /** True while the HUD draws into the cache, when the adapter must skip the layers it draws live. */
    public static boolean cachedPass() {
        return cachedPass;
    }

    /** Draws the live layers, refreshes the cache when due, and composites it over the frame. */
    public static void render(GuiGraphics graphics, Runnable liveLayers, Runnable original) {
        Minecraft minecraft = Minecraft.getInstance();
        graphics.flush();
        liveLayers.run();
        graphics.flush();
        RenderTarget main = minecraft.getMainRenderTarget();
        boolean sizeChanged = target == null || target.width != main.width || target.height != main.height;
        long now = System.nanoTime();
        if (sizeChanged || now - lastRefresh >= REFRESH_NANOS) {
            refresh(minecraft, main, graphics, sizeChanged, original);
            lastRefresh = now;
        }
        composite(main);
    }

    private static void refresh(Minecraft minecraft, RenderTarget main, GuiGraphics graphics, boolean sizeChanged, Runnable original) {
        if (target == null) {
            target = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            target.setClearColor(0.0F, 0.0F, 0.0F, HudCacheTarget.tracksWorldVisibility() ? 1.0F : 0.0F);
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
        HudCacheTarget.begin(target.frameBufferId);
        try {
            original.run();
            graphics.flush();
        } finally {
            HudCacheTarget.end();
            cachedPass = false;
            targets.overhaul$setMainRenderTarget(main);
            main.bindWrite(true);
        }
    }

    private static void composite(RenderTarget main) {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        // The cached pass blends onto black, which leaves premultiplied color in the buffer.
        RenderSystem.blendFunc(GL11.GL_ONE, HudCacheTarget.tracksWorldVisibility() ? GL11.GL_SRC_ALPHA : GL11.GL_ONE_MINUS_SRC_ALPHA);
        // The GUI textured shader discards zero alpha, which here can mean a fully opaque HUD pixel.
        target.blitToScreen(main.width, main.height, false);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
