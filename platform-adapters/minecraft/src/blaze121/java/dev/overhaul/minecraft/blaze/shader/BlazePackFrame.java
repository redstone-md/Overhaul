package dev.overhaul.minecraft.blaze.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeTarget;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.graph.PackPreflight;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackBuiltinMacros;
import dev.overhaul.shader.pack.PackDiagnostic;
import dev.overhaul.shader.pack.PackRenderTargets;
import dev.overhaul.shader.pack.ShaderPackSource;
import dev.overhaul.vulkan.PackGraphRuntime;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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
    private Properties file;
    private BlazePackConfiguration installed, blocked;
    private CompiledShaderPack pack;
    private PackGraphRuntime runtime;
    private int width, height;
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

    public void begin(Camera camera, float partial, Matrix4f view, Matrix4f projection) {
        active = false;
        if (minecraft.level == null) return;
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
            return;
        }
        if (configuration.equals(blocked)) return;
        RenderTarget main = minecraft.getMainRenderTarget();
        try {
            if (runtime == null || width != main.width || height != main.height || !configuration.equals(installed)) {
                install(configuration, main.width, main.height);
            }
            uniforms.capture(new BlazePackCapture(minecraft, camera, partial, view, projection, width, height), pack);
            runtime.beginFrame(renderer.commands(), frame(main), uniforms);
            active = true;
        } catch (RuntimeException | IOException failure) {
            fail(configuration, failure);
        }
    }

    /** Idempotent within a frame: the game calls this before every translucent section layer it draws. */
    public void afterOpaque() {
        if (!active) return;
        try {
            runtime.afterOpaque(renderer.commands(), frame(minecraft.getMainRenderTarget()), uniforms);
        } catch (RuntimeException failure) {
            fail(installed, failure);
        }
    }

    public void finish() {
        if (!active) return;
        active = false;
        try {
            runtime.render(renderer.commands(), frame(minecraft.getMainRenderTarget()), uniforms);
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
            runtime = new PackGraphRuntime(renderer.context(), renderer.allocator(), compiled, targetWidth, targetHeight, compiler, Set.of(), false);
            pack = compiled;
            installed = configuration;
            width = targetWidth;
            height = targetHeight;
            uniforms.resetHistory();
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
        return new PackGraphRuntime.Frame(target.colorView(), target.depthView(), new PackRenderTargets.Color(fog[0], fog[1], fog[2], 1), Map.of());
    }

    private void fail(BlazePackConfiguration configuration, Exception failure) {
        active = false;
        blocked = configuration;
        LOG.error("Shader pack disabled: {}", configuration == null ? "unknown" : configuration.pack(), failure);
        release();
    }

    private void release() {
        if (runtime == null) return;
        renderer.commands();
        renderer.context().waitIdle();
        runtime.close();
        runtime = null;
        pack = null;
        installed = null;
    }

    @Override
    public void close() {
        active = false;
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
