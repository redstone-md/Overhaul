package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Drives {@link BlazeOptimization#OVERHAUL_SHADING} across one world frame. When the frame starts, the surfaces image
 * beside the main colour is cleared; solid and cutout terrain then draws with the programs {@link BlazeShadingSource}
 * makes, into a target over both, so the colour holds each surface's own colour and the surfaces image its normal
 * and light levels. Once cutout is drawn, {@link BlazeShadingPass} lights those pixels in place, before translucent
 * terrain, entities and particles draw over them as the game lights them. It only runs where no shader pack or
 * Fabulous graphics owns the frame; any failure turns it off until the settings change. Render thread only.
 */
public final class BlazeShading {
    private static final BlazeShading INSTANCE = new BlazeShading();

    private BlazeRenderer owner;
    /** The main target as the frame started, and the target over its colour and the surfaces drawn terrain writes. */
    private BlazeTarget main, target;
    private GpuImage surfaces;
    private GpuImageView surfacesView, forColor, forDepth;
    private final BlazeShadingPass[] passes = new BlazeShadingPass[2];
    private int revision = -1;
    private boolean collecting, lit, failed;
    /** What the log last said: 0 nothing, 1 shadows from sky light, 2 traced shadows. */
    private int logged;

    private BlazeShading() {}

    public static BlazeShading get() {
        return INSTANCE;
    }

    /** The draw that shaded terrain goes to: the game's snapshot on a shaded program, its compact twin and the target. */
    record Route(BlazeShaderSnapshot shader, BlazeProgramCompiler.Program compact, BlazeTarget target) {}

    /** Whether this frame's terrain was lit here, so nothing else lights it again. */
    boolean litThisFrame() {
        return lit;
    }

    /** Clears the surfaces when this frame's terrain is to be shaded here. */
    public void frameStarted(Minecraft client) {
        collecting = lit = false;
        if (revision != BlazeOptimization.revision()) {
            revision = BlazeOptimization.revision();
            failed = false;
        }
        if (failed || !BlazeOptimization.OVERHAUL_SHADING.on() || client.level == null) return;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null || renderer.packScene() != null || Minecraft.useShaderTransparency() || !renderer.context().storageFormats()) return;
        try {
            if (renderer != owner) adopt(renderer);
            main = renderer.target(client.getMainRenderTarget().frameBufferId);
            if (main == null || main.depthView() == null) return;
            fit(renderer, main);
            renderer.clearImage(surfaces, 0, 0, 0, 0, 0, 0);
            collecting = true;
        } catch (RuntimeException | Error failure) {
            fail(failure);
        }
    }

    /**
     * Where a terrain layer drawn now goes while surfaces are collected: solid and cutout terrain on the main target
     * draws with the shaded programs; anything else, or a program that cannot be shaded, draws as usual (null).
     * {@code compact} is the layer's program for compact meshes, null when it has none.
     */
    Route route(BlazeRenderer renderer, BlazeShaderAccess shader, BlazeShaderSnapshot game, BlazeProgramCompiler.Program compact) {
        if (!collecting || renderer != owner || renderer.currentTarget() != main) return null;
        BlazeProgramCompiler.Program full = shader.overhaul$shadedSectionProgram(false);
        if (full == null) return null;
        BlazeProgramCompiler.Program shadedCompact = null;
        if (compact != null) {
            shadedCompact = shader.overhaul$shadedSectionProgram(true);
            if (shadedCompact == null) return null;
            renderer.shape(shadedCompact, new BlazeSectionShape(shadedCompact.bindings(), 2));
        }
        renderer.shape(full, new BlazeSectionShape(full.bindings(), 2));
        return new Route(game.withProgram(full), shadedCompact, target);
    }

    /** Lights the terrain drawn since the frame started, with the camera matrices it was drawn with. */
    public void terrainDrawn(Minecraft client, ViewArea area, Camera camera, float partialTick, Matrix4f view, Matrix4f projection) {
        if (!collecting) return;
        collecting = false;
        ClientLevel world = client.level;
        if (world == null) return;
        try {
            owner.flushCapturedDraws();
            Matrix4f viewProjection = new Matrix4f(projection).mul(view);
            var position = camera.getPosition();
            boolean traced = area != null && owner.context().rayQuery() && BlazeOptimization.RAY_TRACING.on() && BlazeOptimization.RT_SHADOWS.on();
            BlazeTextures.Sampled atlas = traced ? owner.textures().resolve(client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)) : null;
            BlazeRayScene.Frame scene = atlas != null ? BlazeRayTracing.get().shadowScene(owner, world, area, position) : null;
            traced = scene != null;
            int variant = traced ? 1 : 0;
            if (passes[variant] == null) passes[variant] = new BlazeShadingPass(owner, traced);
            var lighting = new BlazeShadingPass.Lighting(viewProjection.invert(new Matrix4f()), BlazeRayTracing.light(world, partialTick),
                    BlazeRayTracing.sky(world, position, partialTick), RenderSystem.getShaderFogColor().clone(), RenderSystem.getShaderFogStart(),
                    RenderSystem.getShaderFogEnd(), traced ? BlazeRayTracing.get().reach() : 0);
            passes[variant].shade(main, surfacesView, atlas, scene, lighting);
            lit = true;
            int shown = traced ? 2 : 1;
            if (logged != shown) LoggerFactory.getLogger(BlazeShading.class).info("Overhaul shading on, sun shadows {}", traced ? "traced" : "from sky light");
            logged = shown;
        } catch (RuntimeException | Error failure) {
            fail(failure);
        }
    }

    /** Makes the surfaces and the target over them again when the main target's images changed. */
    private void fit(BlazeRenderer renderer, BlazeTarget main) {
        if (target != null && forColor == main.colorView() && forDepth == main.depthView() && surfaces.width(0) == main.width()
                && surfaces.height(0) == main.height()) return;
        release(renderer);
        surfaces = renderer.allocator().createImage(ImageFormat.RGBA8_UNORM, main.width(), main.height(), 1, 1,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        surfacesView = surfaces.view(0, 1, 0, 1);
        forColor = main.colorView();
        forDepth = main.depthView();
        target = renderer.packTarget(List.of(forColor, surfacesView), forDepth);
    }

    private void release(BlazeRenderer renderer) {
        if (target != null) renderer.retire(target);
        if (surfaces != null) renderer.retire(surfaces);
        target = null;
        surfaces = null;
        surfacesView = forColor = forDepth = null;
    }

    private void fail(Throwable failure) {
        failed = true;
        collecting = false;
        LoggerFactory.getLogger(BlazeShading.class).error("Overhaul shading stopped; terrain draws as the game lights it until the settings change", failure);
    }

    /** A new renderer owns none of the old one's objects; they close with the renderer that made them. */
    private void adopt(BlazeRenderer renderer) {
        owner = renderer;
        main = target = null;
        surfaces = null;
        surfacesView = forColor = forDepth = null;
        passes[0] = passes[1] = null;
        renderer.closeWith(() -> {
            if (owner != renderer) return;
            for (int i = 0; i < 2; i++) if (passes[i] != null) passes[i].close();
            if (target != null) target.close();
            if (surfaces != null) surfaces.close();
            passes[0] = passes[1] = null;
            target = null;
            surfaces = null;
        });
    }
}
