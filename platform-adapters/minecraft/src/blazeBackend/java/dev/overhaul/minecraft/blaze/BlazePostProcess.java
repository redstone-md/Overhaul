package dev.overhaul.minecraft.blaze;

import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/**
 * Anti-aliasing and sharpening of the finished world and hand, before the GUI draws over them. {@code antiAliasing}
 * picks {@code off} or {@code fxaa}, and {@code sharpening} 0 to 100 sharpens after it. While FSR2 or DLSS draws the
 * frame they already smooth and sharpen it, so this pass waits; it also waits under a shader pack, which brings its
 * own. Render thread only.
 */
public final class BlazePostProcess {
    private static final BlazePostProcess INSTANCE = new BlazePostProcess();

    private BlazeRenderer owner;
    private BlazePostPass pass;
    private boolean fxaa, failed;
    private float sharpening;
    private int revision = -1;

    private BlazePostProcess() {}

    public static BlazePostProcess get() {
        return INSTANCE;
    }

    /** Smooths and sharpens what the world pass just left in the main target. */
    public void worldDone(Minecraft client) {
        if (revision != BlazeOptimization.revision()) reread();
        if (!fxaa && sharpening <= 0 || failed || client.level == null) return;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null || renderer.packScene() != null || BlazeUpscale.get().describe() != null || !renderer.context().storageFormats()) return;
        int id = client.getMainRenderTarget().frameBufferId;
        if (id < 0) return;
        try {
            if (renderer != owner) adopt(renderer);
            if (pass == null) pass = new BlazePostPass(renderer);
            renderer.flushCapturedDraws();
            BlazeTarget target = renderer.target(id);
            if (fxaa) pass.run(target, BlazePostShader.FXAA, 0);
            if (sharpening > 0) pass.run(target, BlazePostShader.SHARPEN, sharpening);
        } catch (RuntimeException | Error failure) {
            failed = true;
            LoggerFactory.getLogger(BlazePostProcess.class).error("Anti-aliasing stopped; the world draws without it until the settings change", failure);
        }
    }

    private void reread() {
        revision = BlazeOptimization.revision();
        fxaa = BlazeOptimization.value("antiAliasing", "off").equalsIgnoreCase("fxaa");
        try {
            sharpening = Math.max(0, Math.min(100, Float.parseFloat(BlazeOptimization.value("sharpening", "0")))) / 100;
        } catch (NumberFormatException ignored) {
            sharpening = 0;
        }
        failed = false;
    }

    /** A new renderer owns none of the old one's objects; the pass closes with the renderer that made it. */
    private void adopt(BlazeRenderer renderer) {
        owner = renderer;
        pass = null;
        renderer.closeWith(() -> {
            if (owner != renderer) return;
            if (pass != null) pass.close();
            pass = null;
        });
    }
}
