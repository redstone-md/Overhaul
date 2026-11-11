package dev.overhaul.minecraft.blaze;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

/**
 * Drives ray traced lighting across one world frame: once the world is drawn, the terrain near the camera becomes
 * acceleration structures and one compute pass shades the finished picture with them. It only runs where the device
 * offers ray queries, no shader pack or Fabulous graphics owns the frame and at least one of its effects is on;
 * anything else, or any failure, leaves the frame as the game drew it. Render thread only.
 */
public final class BlazeRayTracing {
    private static final BlazeRayTracing INSTANCE = new BlazeRayTracing();
    /** Shows only the traced light, grey on white, to check shadows and occlusion by eye. */
    private static final boolean SHOW_LIGHT = Boolean.getBoolean("overhaul.dev.rtShowLight");

    private BlazeRenderer owner;
    private BlazeRayScene scene;
    private BlazeRayPass pass;
    private Object level;
    private int frame;
    /** How far rays reach, in blocks; read when a renderer is adopted, once its card's class is known. */
    private int distance = 64;
    private boolean failed, logged, packNoted;

    private BlazeRayTracing() {}

    public static BlazeRayTracing get() {
        return INSTANCE;
    }

    /** Lights the world just drawn into the main target, with the camera matrices it was drawn with. */
    public void worldDrawn(Minecraft client, ViewArea area, Camera camera, float partialTick, Matrix4f view, Matrix4f projection) {
        if (!BlazeOptimization.RAY_TRACING.on() || failed || area == null) return;
        int flags = (BlazeOptimization.RT_SHADOWS.on() ? BlazeRayShader.SHADOWS : 0) | (BlazeOptimization.RT_AO.on() ? BlazeRayShader.AMBIENT_OCCLUSION : 0)
                | (BlazeOptimization.RT_REFLECTIONS.on() ? BlazeRayShader.REFLECTIONS : 0);
        ClientLevel world = client.level;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (flags == 0 || world == null || renderer == null || !renderer.context().rayQuery()) return;
        if (renderer.packScene() != null) {
            if (!packNoted) LoggerFactory.getLogger(BlazeRayTracing.class).info("Ray traced lighting waits while a shader pack lights the world");
            packNoted = true;
            return;
        }
        packNoted = false;
        if (Minecraft.useShaderTransparency() || !renderer.context().storageFormats()) return;
        try {
            if (renderer != owner) adopt(renderer);
            if (world != level) {
                level = world;
                if (scene != null) owner.retire(scene);
                scene = null;
            }
            if (scene == null) scene = new BlazeRayScene(renderer);
            if (pass == null) pass = new BlazeRayPass(renderer);
            BlazeTextures.Sampled atlas = renderer.textures().resolve(client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS));
            if (atlas == null) return;
            renderer.flushCapturedDraws();
            Vec3 position = camera.getPosition();
            BlazeRayScene.Frame traced = scene.prepare(area, position.x, position.y, position.z, distance);
            if (traced == null) return;
            if (BlazeUpscale.get().describe() != null) flags |= BlazeRayShader.MOVING_NOISE;
            if (SHOW_LIGHT) flags |= BlazeRayShader.SHOW_LIGHT;
            var lighting = new BlazeRayPass.Lighting(new Matrix4f(projection).mul(view).invert(), light(world, partialTick),
                    sky(world, position, partialTick), flags, frame++, distance);
            pass.trace(renderer.target(client.getMainRenderTarget().frameBufferId), atlas, traced, lighting);
            if (!logged) LoggerFactory.getLogger(BlazeRayTracing.class).info("Ray traced lighting on, {} blocks, {} instances", distance, traced.instances());
            logged = true;
        } catch (RuntimeException | Error failure) {
            failed = true;
            LoggerFactory.getLogger(BlazeRayTracing.class).error("Ray traced lighting stopped; the world draws without it from now on", failure);
        }
    }

    /** The direction toward the sun, or the moon once the sun has set, and how strongly it lights the world. */
    private static float[] light(ClientLevel world, float partialTick) {
        double angle = world.getTimeOfDay(partialTick) * Math.PI * 2;
        float x = (float) -Math.sin(angle), y = (float) Math.cos(angle);
        float weather = SHOW_LIGHT ? 1 : 1 - 0.85f * world.getRainLevel(partialTick);
        if (y >= 0) return new float[]{x, y, 0, Math.min(1, y * 4) * weather};
        return new float[]{-x, -y, 0, 0.35f * Math.min(1, -y * 4) * weather};
    }

    private static float[] sky(ClientLevel world, Vec3 position, float partialTick) {
        Vec3 color = world.getSkyColor(position, partialTick);
        return new float[]{(float) color.x, (float) color.y, (float) color.z, world.getSkyDarken(partialTick)};
    }

    /** A new renderer owns none of the old one's objects; they close with the renderer that made them. */
    private void adopt(BlazeRenderer renderer) {
        owner = renderer;
        distance = distance(BlazeOptimization.value("rtDistance", "64"));
        scene = null;
        pass = null;
        level = null;
        renderer.closeWith(() -> {
            if (owner != renderer) return;
            if (scene != null) scene.close();
            if (pass != null) pass.close();
            scene = null;
            pass = null;
        });
    }

    private static int distance(String value) {
        try {
            return Math.max(16, Math.min(256, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return 64;
        }
    }
}
