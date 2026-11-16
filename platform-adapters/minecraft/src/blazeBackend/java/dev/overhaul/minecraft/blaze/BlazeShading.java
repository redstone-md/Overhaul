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
 * terrain, entities and particles draw over them as the game lights them. With {@link BlazeOptimization#SHADING_BLOOM}
 * or {@link BlazeOptimization#SHADING_EXPOSURE} on, the pass also leaves linear light at half size, which
 * {@link BlazeShadingGlow} turns into the glow and the exposure the next frame shows. Without traced shadows, {@link BlazeOptimization#SHADING_SCREEN_SHADOWS} has the pass find the
 * shadows of what is on screen. It only runs where no shader pack or
 * Fabulous graphics owns the frame; any failure turns it off until the settings change. Render thread only.
 */
public final class BlazeShading {
    private static final BlazeShading INSTANCE = new BlazeShading();
    /** How far, in blocks, the plain variant walks the screen toward the sun at middle distance. */
    private static final float SCREEN_SHADOW_REACH = 16;

    private BlazeRenderer owner;
    /** The main target as the frame started, and the target over its colour and the surfaces drawn terrain writes. */
    private BlazeTarget main, target;
    private GpuImage surfaces;
    private GpuImageView surfacesView, forColor, forDepth;
    /** The lighting passes made so far, by what each does: 1 for traced shadows, 2 for leaving linear light, 4 for the air catching the sun. */
    private final BlazeShadingPass[] passes = new BlazeShadingPass[8];
    private BlazeShadingGlow glow;
    private int revision = -1;
    private boolean collecting, lit, failed;
    /** Whether this frame's terrain lights itself as it is drawn: nothing asked needs the depth or the light of the whole frame. */
    private boolean direct;
    /** Whether the lighting pass left the surfaces wiped, as it does each frame it runs. */
    private boolean clean;
    /** What the log last said: 0 nothing, 1 shadows from sky light, 2 traced shadows, 3 terrain lighting itself. */
    private int logged;
    /** What mirrored translucent terrain takes this frame, under the names its program gives them. */
    private final java.util.Map<String, float[]> mirrorLight = new java.util.HashMap<>();

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

    /**
     * The colour clouds take while terrain is lit here: the game's own, warmed by the sun's light as the ground is: a
     * little all day, and fully at dawn and dusk.
     */
    public net.minecraft.world.phys.Vec3 clouds(ClientLevel world, net.minecraft.world.phys.Vec3 game, float partialTick) {
        if (!lit || world == null) return game;
        float height = (float) Math.cos(world.getTimeOfDay(partialTick) * Math.PI * 2);
        if (height < -0.1f) return game;
        float along = Math.min(1, Math.max(0, (height + 0.1f) / 0.55f)), low = 0.3f + 0.7f * (1 - along * along * (3 - 2 * along));
        return new net.minecraft.world.phys.Vec3(game.x * (1 + 0.25 * low), game.y * (1 - 0.08 * low), game.z * (1 - 0.42 * low));
    }

    /** Clears the surfaces when this frame's terrain is to be shaded here, or hands terrain the light to shade itself with. */
    public void frameStarted(Minecraft client, Camera camera, float partialTick) {
        collecting = lit = direct = false;
        if (revision != BlazeOptimization.revision()) {
            revision = BlazeOptimization.revision();
            failed = false;
        }
        if (client.level != null) BlazeDevClock.hold(client.level);
        BlazeDevCommands.run(client);
        if (failed || !BlazeOptimization.OVERHAUL_SHADING.on() || client.level == null) return;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null || renderer.packScene() != null || Minecraft.useShaderTransparency() || !renderer.context().storageFormats()) return;
        try {
            if (renderer != owner) adopt(renderer);
            main = renderer.target(client.getMainRenderTarget().frameBufferId);
            if (main == null || main.depthView() == null) return;
            if (!BlazeOptimization.SHADING_SCREEN_SHADOWS.on() && !BlazeOptimization.SHADING_BLOOM.on() && !BlazeOptimization.SHADING_EXPOSURE.on() && !BlazeOptimization.SHADING_SHAFTS.on()
                    && !(renderer.context().rayQuery() && BlazeOptimization.RAY_TRACING.on() && BlazeOptimization.RT_SHADOWS.on())) {
                light(client.level, camera.getPosition(), partialTick);
                lit = direct = true;
                log(3);
                return;
            }
            fit(renderer, main);
            // The lighting pass wipes the surfaces it read; only ones it did not get to are cleared here.
            if (!clean) renderer.clearImage(surfaces, 0, 0, 0, 0, 0, 0);
            clean = false;
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
        if (!collecting && !lit || renderer != owner || renderer.currentTarget() != main) return null;
        BlazeProgramCompiler.Program full = shader.overhaul$shadedSectionProgram(false, direct);
        if (full == null) return null;
        // Opaque layers write their surfaces while those are collected, or light themselves; translucent ones mirror
        // the sky once lit.
        boolean mirror = BlazeShadingSource.mirrored(full), widened = mirror || direct;
        if (mirror ? !lit || mirrorLight.isEmpty() || !BlazeOptimization.SHADING_WATER.on() : !collecting && !direct) return null;
        int outputs = widened ? 1 : 2;
        BlazeProgramCompiler.Program shadedCompact = null;
        if (compact != null) {
            shadedCompact = shader.overhaul$shadedSectionProgram(true, direct);
            if (shadedCompact == null) return null;
            renderer.shape(shadedCompact, new BlazeSectionShape(shadedCompact.bindings(), outputs));
        }
        renderer.shape(full, new BlazeSectionShape(full.bindings(), outputs));
        return widened ? new Route(game.widened(full, mirrorLight), shadedCompact, main) : new Route(game.withProgram(full), shadedCompact, target);
    }

    /** Lights the terrain drawn since the frame started, with the camera matrices it was drawn with. */
    public void terrainDrawn(Minecraft client, ViewArea area, Camera camera, float partialTick, Matrix4f view, Matrix4f projection) {
        if (!collecting) return;
        collecting = false;
        ClientLevel world = client.level;
        if (world == null) return;
        try {
            owner.flushCapturedDraws();
            owner.gpuMark("shadedTerrain");
            Matrix4f viewProjection = new Matrix4f(projection).mul(view);
            var position = camera.getPosition();
            boolean traced = area != null && owner.context().rayQuery() && BlazeOptimization.RAY_TRACING.on() && BlazeOptimization.RT_SHADOWS.on();
            BlazeTextures.Sampled atlas = traced ? owner.textures().resolve(client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)) : null;
            BlazeRayScene.Frame scene = atlas != null ? BlazeRayTracing.get().shadowScene(owner, world, area, position) : null;
            traced = scene != null;
            boolean bloom = BlazeOptimization.SHADING_BLOOM.on(), adapt = BlazeOptimization.SHADING_EXPOSURE.on(), linear = bloom || adapt;
            boolean shafts = BlazeOptimization.SHADING_SHAFTS.on();
            int variant = (traced ? 1 : 0) | (linear ? 2 : 0) | (shafts ? 4 : 0);
            if (passes[variant] == null) passes[variant] = new BlazeShadingPass(owner, traced, linear, shafts);
            if (linear && glow == null) glow = new BlazeShadingGlow(owner);
            light(world, position, partialTick);
            var lighting = new BlazeShadingPass.Lighting(viewProjection.invert(new Matrix4f()), mirrorLight.get(BlazeShadingSource.SUN),
                    mirrorLight.get(BlazeShadingSource.SKY), RenderSystem.getShaderFogColor().clone(), RenderSystem.getShaderFogStart(),
                    RenderSystem.getShaderFogEnd(), traced ? BlazeRayTracing.get().reach() : BlazeOptimization.SHADING_SCREEN_SHADOWS.on() ? SCREEN_SHADOW_REACH : 0);
            passes[variant].shade(main, surfacesView, atlas, scene, lighting, linear ? glow.light(main.width(), main.height(), bloom, adapt) : null);
            if (linear) glow.finish(bloom, adapt);
            owner.gpuMark("shading");
            lit = clean = true;
            log(traced ? 2 : 1);
        } catch (RuntimeException | Error failure) {
            fail(failure);
        }
    }

    /** Works out the frame's light: the sun or moon, the sky, and where the eye is and when. */
    private void light(ClientLevel world, net.minecraft.world.phys.Vec3 position, float partialTick) {
        float[] light = BlazeRayTracing.light(world, partialTick);
        // No sun or moon where the sky gives no light.
        if (!world.dimensionType().hasSkyLight()) light[3] = 0;
        mirrorLight.put(BlazeShadingSource.SUN, light);
        mirrorLight.put(BlazeShadingSource.SKY, BlazeRayTracing.sky(world, position, partialTick));
        mirrorLight.put(BlazeShadingSource.EYE, new float[]{(float) (position.x % 1024), (float) (position.y % 1024), (float) (position.z % 1024),
                (world.getGameTime() % 24000 + partialTick) / 20f});
    }

    private void log(int shown) {
        if (logged != shown) LoggerFactory.getLogger(BlazeShading.class).info("Overhaul shading on, sun shadows {}",
                shown == 2 ? "traced" : shown == 3 ? "from sky light, terrain lighting itself" : "from sky light");
        logged = shown;
    }

    /** Makes the surfaces and the target over them again when the main target's images changed. */
    private void fit(BlazeRenderer renderer, BlazeTarget main) {
        if (target != null && forColor == main.colorView() && forDepth == main.depthView() && surfaces.width(0) == main.width()
                && surfaces.height(0) == main.height()) return;
        release(renderer);
        surfaces = renderer.allocator().createImage(ImageFormat.RGBA8_UNORM, main.width(), main.height(), 1, 1,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        clean = false;
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
        java.util.Arrays.fill(passes, null);
        glow = null;
        renderer.closeWith(() -> {
            if (owner != renderer) return;
            for (BlazeShadingPass pass : passes) if (pass != null) pass.close();
            if (glow != null) glow.close();
            if (target != null) target.close();
            if (surfaces != null) surfaces.close();
            java.util.Arrays.fill(passes, null);
            glow = null;
            target = null;
            surfaces = null;
        });
    }
}
