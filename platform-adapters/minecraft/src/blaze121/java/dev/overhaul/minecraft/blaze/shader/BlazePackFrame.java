package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazePackDistant;
import dev.overhaul.minecraft.blaze.BlazePackScene;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeTarget;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.graph.PackPreflight;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackBuiltinMacros;
import dev.overhaul.shader.pack.PackDhPrograms;
import dev.overhaul.shader.pack.PackDiagnostic;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.ShaderPackSource;
import dev.overhaul.vulkan.PackGraphRuntime;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ViewArea;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a shader pack's fullscreen passes around the game's own world rendering. A pack that cannot run is reported
 * once and the game keeps drawing without it: a broken pack must not cost the player their world.
 */
public final class BlazePackFrame implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BlazePackFrame.class);
    private final Minecraft minecraft;
    private final BlazeRenderer renderer;
    private final BlazePackUniforms uniforms = new BlazePackUniforms();
    private final Matrix4f distantProjection = new Matrix4f();
    private Properties file;
    private BlazePackConfiguration installed, blocked;
    private CompiledShaderPack pack;
    private PackGraphRuntime runtime;
    private BlazePackScene scene;
    private BlazePackShadows shadows;
    private int width, height, generation;
    private boolean active;

    private BlazePackFrame(Minecraft minecraft, BlazeRenderer renderer) {
        this.minecraft = minecraft;
        this.renderer = renderer;
        renderer.closeWith(this);
    }

    /** Null when this game is not on the owned device. */
    public static BlazePackFrame open(Minecraft minecraft) {
        BlazeRenderer renderer = BlazeFrontend.current();
        return renderer == null ? null : new BlazePackFrame(minecraft, renderer);
    }

    public void begin(Camera camera, float partial, Matrix4f view, Matrix4f projection, LightTexture light, ViewArea sections) {
        active = false;
        if (minecraft.level == null) return;
        if (generation != BlazePackRequest.generation()) {
            generation = BlazePackRequest.generation();
            invalidate();
        }
        BlazePackConfiguration configuration;
        try {
            if (file == null) file = BlazePackConfiguration.read(minecraft);
            configuration = BlazePackConfiguration.capture(minecraft, file);
        } catch (RuntimeException failure) {
            LOG.error("Shader pack configuration is unusable", failure);
            file = new Properties();
            return;
        }
        if (!configuration.configured()) {
            release();
            BlazePackRequest.report("Off");
            return;
        }
        if (configuration.equals(blocked)) return;
        RenderTarget main = minecraft.getMainRenderTarget();
        try {
            if (runtime == null || width != main.width || height != main.height || !configuration.equals(installed)) {
                install(configuration, main.width, main.height);
            }
            uniforms.capture(new BlazePackCapture(minecraft, camera, partial, view, projection, width, height), pack);
            runtime.beginFrame(renderer.packCommands(), frame(main), uniforms);
            if (scene != null) {
                scene.begin(renderer.target(main.frameBufferId));
                scene.distantCamera(view, uniforms.distantProjection(distantProjection));
            }
            if (shadows != null) {
                shadows.render(minecraft, sections, uniforms, camera, light, frame(main));
                scene.shadowsDone();
            }
            active = true;
        } catch (RuntimeException | IOException failure) {
            fail(configuration, failure);
        }
    }

    /** True while an installed pack draws its own sky clouds instead of the game's. */
    public boolean hidesClouds() {
        return active && !pack.vanillaClouds();
    }

    /** Marks the game's rain and snow draws, which a pack draws with its weather program. */
    public void weather(boolean drawing) {
        if (active && scene != null) scene.weather(drawing);
    }

    /** Idempotent within a frame: the game calls this before every translucent section layer it draws. */
    public void afterOpaque() {
        if (!active) return;
        try {
            runtime.afterOpaque(renderer.packCommands(), frame(minecraft.getMainRenderTarget()), uniforms);
            if (scene != null) scene.afterOpaque();
        } catch (RuntimeException failure) {
            fail(installed, failure);
        }
    }

    /** Draws the first-person hand through the pack's hand programs, before its composites run over the frame. */
    public void hand(net.minecraft.client.renderer.GameRenderer game, Camera camera, float partial, Matrix4f viewRotation) {
        if (!active || scene == null || !BlazePackHand.wanted(minecraft, game)) return;
        scene.hand(true);
        try {
            BlazePackHand.draw(minecraft, game, camera, partial, viewRotation);
        } catch (RuntimeException failure) {
            fail(installed, failure);
        } finally {
            if (scene != null) scene.hand(false);
        }
    }

    public void finish() {
        if (!active) return;
        active = false;
        if (scene != null) scene.end();
        try {
            runtime.render(renderer.packCommands(), frame(minecraft.getMainRenderTarget()), uniforms);
            if (scene != null) scene.drawOnTop();
            uniforms.complete();
        } catch (RuntimeException failure) {
            fail(installed, failure);
        }
    }

    /** Resources changed, or the level did: read the request again and rebuild on the next frame. */
    public void invalidate() {
        active = false;
        file = null;
        blocked = null;
        release();
    }

    private void install(BlazePackConfiguration configuration, int targetWidth, int targetHeight) throws IOException {
        release();
        long started = System.nanoTime();
        try (ShaderPackSource source = ShaderPackSource.open(configuration.pack()); ShadercCompiler compiler = new ShadercCompiler()) {
            Map<String, String> definitions = PackBuiltinMacros.with(configuration.definitions(), SharedConstants.getCurrentVersion().getName(), System.getProperty("os.name"));
            if (BlazePackDistant.loaded()) {
                definitions = new HashMap<>(definitions);
                for (var macro : PackDhPrograms.macros().entrySet()) definitions.putIfAbsent(macro.getKey(), macro.getValue());
            }
            Map<String, Boolean> options = configuration.selectedOptions();
            String dimension = minecraft.level.dimension().location().toString();
            CompiledShaderPack compiled;
            try {
                compiled = CompiledShaderPack.compileForDimension(source, dimension, options, definitions, compiler);
            } catch (RuntimeException failure) {
                throw describe(source, dimension, options, definitions, compiler, failure);
            }
            renderer.commands();
            renderer.context().waitIdle();
            runtime = new PackGraphRuntime(renderer.context(), renderer.allocator(), compiled, targetWidth, targetHeight, compiler, BlazePackScene.worldPrograms(compiled), false);
            if (BlazeOptimization.PACK_FRAME_QUEUE.on()) runtime.recordInto(renderer.packRecorder());
            if (BlazePackScene.drawsWorld(compiled)) scene = new BlazePackScene(renderer, compiled, runtime, uniforms);
            if (scene != null && scene.castsShadows()) shadows = new BlazePackShadows(renderer, scene, runtime, compiled.shadow());
            pack = compiled;
            installed = configuration;
            width = targetWidth;
            height = targetHeight;
            uniforms.resetHistory();
            BlazePackRequest.report("Active: " + configuration.pack().getFileName() + ", compiled in " + (System.nanoTime() - started) / 1_000_000 + " ms");
            LOG.info("Shader pack installed: {} ({}x{}) in {} ms", configuration.pack(), targetWidth, targetHeight, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private static RuntimeException describe(ShaderPackSource source, String dimension, Map<String, Boolean> options, Map<String, String> macros, ShadercCompiler compiler, RuntimeException failure) {
        try {
            var blockers = PackPreflight.analyze(source, dimension, options, macros, compiler).diagnostics().stream()
                    .filter(PackDiagnostic::blocking).map(PackDiagnostic::toString).toList();
            return blockers.isEmpty() ? failure : new IllegalStateException("Shader pack cannot load, " + blockers.size() + " blocker(s):\n" + String.join("\n", blockers), failure);
        } catch (IOException | RuntimeException secondary) {
            failure.addSuppressed(secondary);
            return failure;
        }
    }

    private PackGraphRuntime.Frame frame(RenderTarget main) {
        BlazeTarget target = renderer.target(main.frameBufferId);
        float[] fog = uniforms.fog();
        return new PackGraphRuntime.Frame(target.colorView(), target.depthView(), new PackRenderTargets.Color(fog[0], fog[1], fog[2], 1),
                scene == null ? Map.of() : scene.distantTextures());
    }

    private void fail(BlazePackConfiguration configuration, Exception failure) {
        active = false;
        blocked = configuration;
        Throwable reason = failure;
        while (reason.getMessage() == null && reason.getCause() != null) reason = reason.getCause();
        BlazePackRequest.report("Failed: " + (reason.getMessage() == null ? reason.getClass().getSimpleName() : reason.getMessage()));
        LOG.error("Shader pack disabled: {}", configuration == null ? "unknown" : configuration.pack(), failure);
        release();
    }

    private void closeScene() {
        shadows = null;
        if (scene == null) return;
        scene.close();
        scene = null;
    }

    private void release() {
        if (runtime == null) return;
        renderer.commands();
        renderer.context().waitIdle();
        closeScene();
        runtime.close();
        runtime = null;
        pack = null;
        installed = null;
    }

    @Override
    public void close() {
        active = false;
        closeScene();
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
