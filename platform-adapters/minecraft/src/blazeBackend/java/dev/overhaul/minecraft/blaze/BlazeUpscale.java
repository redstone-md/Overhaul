package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.overhaul.vulkan.GpuImage;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.LoggerFactory;

/**
 * Drives FSR2 across one world frame. The world draws into a smaller target swapped in for the game's main one, under
 * a projection offset by FSR's jitter; once the hand is drawn, the motion pass and FSR run and the upscaled frame lands
 * in the real main target, which goes back before the GUI draws. Anything it cannot do leaves the frame unscaled.
 * Render thread only.
 */
public final class BlazeUpscale {
    private static final BlazeUpscale INSTANCE = new BlazeUpscale();
    private static final int MINIMUM = 64, LINEAR = 9729;
    private static final double TELEPORT = 64;
    private static final float RATIO = ratio(BlazeOptimization.value("fsr2Quality", "quality"));
    private static final float SHARPNESS = sharpness(BlazeOptimization.value("fsr2Sharpness", "0.3"));

    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private final Matrix4f previousView = new Matrix4f(), previousProjection = new Matrix4f();
    private final Vector3d camera = new Vector3d(), previousCamera = new Vector3d();
    private BlazeRenderer owner;
    private BlazeFsr2Pipelines pipelines;
    private BlazeFsr2Motion motion;
    private BlazeFsr2 fsr;
    private BlazeFsr2Motion.Binding binding;
    private BlazeDlss dlss;
    private int[] dlssSize;
    private final int[] dlssDisplay = new int[2];
    private TextureTarget scaled;
    private RenderTarget real;
    private Object level;
    private long lastNanos;
    private float clipX, clipY;
    private boolean active, captured, drawn, history, failed;

    private BlazeUpscale() {}

    public static BlazeUpscale get() {
        return INSTANCE;
    }

    /** @return the smaller target the world draws into this frame, or null to draw it as usual */
    public RenderTarget begin(Minecraft client) {
        active = captured = drawn = false;
        if (!BlazeOptimization.FSR2.on() && !BlazeOptimization.DLSS.on() || failed) return null;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null || client.level == null) return null;
        if (renderer != owner) adopt(renderer);
        if (renderer.packScene() != null || Minecraft.useShaderTransparency() || !renderer.context().storageFormats()) {
            history = false;
            return null;
        }
        RenderTarget main = client.getMainRenderTarget();
        int displayWidth = main.width, displayHeight = main.height;
        if (dlss == null && !BlazeOptimization.FSR2.on()) return null;
        if (dlss != null && (dlssSize == null || dlssDisplay[0] != displayWidth || dlssDisplay[1] != displayHeight)) {
            dlssSize = dlss.renderSize(displayWidth, displayHeight);
            dlssDisplay[0] = displayWidth;
            dlssDisplay[1] = displayHeight;
        }
        int[] size = dlss != null ? dlssSize : null;
        int renderWidth = size != null ? size[0] : Math.max(MINIMUM, Math.round(displayWidth / RATIO));
        int renderHeight = size != null ? size[1] : Math.max(MINIMUM, Math.round(displayHeight / RATIO));
        boolean nativeSize = size != null && dlss.nativeResolution();
        if (renderWidth > displayWidth || renderHeight > displayHeight || !nativeSize && (renderWidth == displayWidth || renderHeight == displayHeight)) return null;
        try {
            prepare(renderer, renderWidth, renderHeight, displayWidth, displayHeight);
        } catch (RuntimeException | Error failure) {
            fail(failure);
            return null;
        }
        if (client.level != level) {
            level = client.level;
            history = false;
        }
        fsr.constants().advanceJitter();
        clipX = fsr.constants().clipJitterX();
        clipY = fsr.constants().clipJitterY();
        real = main;
        active = true;
        return scaled;
    }

    /** Offsets a projection by this frame's jitter while the world draws scaled. */
    public Matrix4f jitter(Matrix4f projection) {
        return active ? new Matrix4f().translation(clipX, clipY, 0).mul(projection) : projection;
    }

    /** Keeps the matrices the world is drawn with, the projection without its jitter. */
    public void capture(Matrix4f view, Matrix4f projection, double x, double y, double z) {
        if (!active) return;
        this.view.set(view);
        this.projection.translation(-clipX, -clipY, 0).mul(projection);
        camera.set(x, y, z);
        captured = true;
    }

    /** Copies the world's depth aside, before the game clears depth to draw the hand. */
    public void worldDrawn() {
        if (!active || !captured) return;
        try {
            BlazeTarget target = owner.target(scaled.frameBufferId);
            if (binding == null || !binding.reads(target.sampledDepthView(), fsr.resources())) {
                if (binding != null) owner.retire(binding);
                binding = null;
                binding = motion.new Binding(target.sampledDepthView(), fsr.resources());
            }
            owner.flushCapturedDraws();
            binding.copyWorld(owner);
            drawn = true;
        } catch (RuntimeException | Error failure) {
            fail(failure);
        }
    }

    /** Upscales the scaled frame into the real main target. @return the target to put back, or null if none was swapped */
    public RenderTarget end() {
        if (!active) return null;
        active = false;
        RenderTarget restore = real;
        real = null;
        GpuImage source = owner.target(scaled.frameBufferId).color(), destination = owner.target(restore.frameBufferId).color();
        boolean upscaled = false;
        try {
            if (drawn && !failed) upscaled = upscale(destination);
        } catch (RuntimeException | Error failure) {
            fail(failure);
        }
        if (!upscaled) {
            owner.blitImage(source, new int[]{0, 0, scaled.width, scaled.height}, destination, new int[]{0, 0, restore.width, restore.height}, LINEAR);
            history = false;
        }
        return restore;
    }

    private boolean upscale(GpuImage destination) {
        boolean reset = !history || camera.distance(previousCamera) > TELEPORT;
        Matrix4f currentToPrevious = new Matrix4f(projection).mul(view).invert();
        if (!reset) {
            currentToPrevious = new Matrix4f(previousProjection).mul(previousView)
                    .translate((float) (camera.x - previousCamera.x), (float) (camera.y - previousCamera.y), (float) (camera.z - previousCamera.z))
                    .mul(currentToPrevious);
        }
        float m22 = projection.m22(), m32 = projection.m32();
        float near = m32 / (m22 - 1), far = m32 / (m22 + 1);
        owner.flushCapturedDraws();
        binding.motion(owner, currentToPrevious, new float[]{m22, m32, near, far});
        long now = System.nanoTime();
        float seconds = lastNanos == 0 ? 1 / 60f : (now - lastNanos) / 1e9f;
        lastNanos = now;
        float verticalFov = (float) (2 * Math.atan(1 / projection.m11()));
        GpuImage color = owner.target(scaled.frameBufferId).color();
        if (dlss != null) {
            fsr.takeColor(color);
            float aspect = projection.m11() / projection.m00();
            Matrix4f toPrevious = reset ? new Matrix4f() : new Matrix4f(currentToPrevious);
            var lens = new BlazeStreamlineStructs.Camera(new Matrix4f().setPerspective(verticalFov, aspect, near, far, true), toPrevious,
                    fsr.constants().jitterX(), fsr.constants().jitterY(), near, far, verticalFov, aspect, reset);
            if (!dlss.evaluate(owner, fsr.resources(), lens)) return false;
        } else if (!fsr.dispatch(color, new BlazeFsr2.Frame(verticalFov, near, far, seconds, reset, SHARPNESS))) return false;
        GpuImage output = fsr.resources().image(BlazeFsr2Resources.Image.OUTPUT);
        owner.blitImage(output, new int[]{0, 0, output.width(0), output.height(0)}, destination, new int[]{0, 0, output.width(0), output.height(0)}, LINEAR);
        previousView.set(view);
        previousProjection.set(projection);
        previousCamera.set(camera);
        history = true;
        return true;
    }

    private void prepare(BlazeRenderer renderer, int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        if (pipelines == null) {
            pipelines = new BlazeFsr2Pipelines(renderer.context(), renderer.allocator());
            renderer.closeWith(pipelines);
        }
        if (motion == null) {
            motion = new BlazeFsr2Motion(renderer.context(), renderer.allocator());
            renderer.closeWith(motion);
        }
        if (fsr == null || !fsr.matches(renderWidth, renderHeight, displayWidth, displayHeight)) {
            dropContext(true);
            fsr = new BlazeFsr2(renderer, pipelines, renderWidth, renderHeight, displayWidth, displayHeight);
            history = false;
            if (dlss != null) LoggerFactory.getLogger(BlazeUpscale.class).info("DLSS upscales {}x{} to {}x{}", renderWidth, renderHeight, displayWidth, displayHeight);
            else LoggerFactory.getLogger(BlazeUpscale.class).info("FSR2 upscales {}x{} to {}x{}, sharpness {}", renderWidth, renderHeight, displayWidth, displayHeight, SHARPNESS);
        }
        if (scaled == null) scaled = new TextureTarget(renderWidth, renderHeight, true, Minecraft.ON_OSX);
        else if (scaled.width != renderWidth || scaled.height != renderHeight) scaled.resize(renderWidth, renderHeight, Minecraft.ON_OSX);
    }

    /** A new renderer owns none of the old one's objects, which closed with it. */
    private void adopt(BlazeRenderer renderer) {
        owner = renderer;
        pipelines = null;
        motion = null;
        fsr = null;
        binding = null;
        scaled = null;
        history = false;
        failed = false;
        dlssSize = null;
        dlss = BlazeOptimization.DLSS.on() ? BlazeDlss.create(renderer.streamline(), renderer.context()) : null;
        if (dlss != null) renderer.closeWith(dlss);
        renderer.closeWith(() -> {
            if (owner == renderer) dropContext(false);
        });
    }

    private void dropContext(boolean retire) {
        if (binding != null) {
            if (retire) owner.retire(binding);
            else binding.close();
        }
        if (fsr != null) {
            if (retire) owner.retire(fsr);
            else fsr.close();
        }
        binding = null;
        fsr = null;
    }

    private void fail(Throwable failure) {
        failed = true;
        history = false;
        LoggerFactory.getLogger(BlazeUpscale.class).error("{} stopped; the world draws at full resolution from now on", dlss != null ? "DLSS" : "FSR2", failure);
    }

    private static float ratio(String quality) {
        return switch (quality.toLowerCase(java.util.Locale.ROOT)) {
            case "quality" -> 1.5f;
            case "balanced" -> 1.7f;
            case "performance" -> 2.0f;
            case "ultra", "ultraperformance", "ultra_performance" -> 3.0f;
            default -> {
                try {
                    yield Math.max(1, Math.min(4, Float.parseFloat(quality)));
                } catch (NumberFormatException ignored) {
                    yield 1.5f;
                }
            }
        };
    }

    private static float sharpness(String value) {
        try {
            return Math.max(0, Math.min(1, Float.parseFloat(value)));
        } catch (NumberFormatException ignored) {
            return 0.3f;
        }
    }
}
