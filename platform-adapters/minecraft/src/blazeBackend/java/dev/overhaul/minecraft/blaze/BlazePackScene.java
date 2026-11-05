package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackProgramFallbacks;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.PackGraphRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Sends the game's world draws through a pack's gbuffer programs while its frame is open. Only draws aimed at the main
 * framebuffer move: a mod's own framebuffer keeps its draws. The pack no longer imports the main colour, so a draw with
 * no pack program lands in its first colour target with the game's own shader.
 */
public final class BlazePackScene implements AutoCloseable {
    private final BlazeRenderer renderer;
    private final CompiledShaderPack pack;
    private final PackGraphRuntime runtime;
    private final PackGraphRuntime.Uniforms uniforms;
    private final Map<String, CompiledShaderPack.Program> programs = new HashMap<>();
    private final Map<String, BlazePackDraw> byShader = new HashMap<>();
    private final Map<CompiledShaderPack.Program, BlazePackDraw> byProgram = new HashMap<>();
    private final Map<List<GpuImageView>, BlazeTarget> targets = new HashMap<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
    private final GpuBuffer constants;
    private final BlazeTextures.Sampled white, flatNormal, noSpecular;
    private BlazeTarget main, plain;
    private int phase;
    private boolean open, opaqueDone;

    public BlazePackScene(BlazeRenderer renderer, CompiledShaderPack pack, PackGraphRuntime runtime, PackGraphRuntime.Uniforms uniforms) {
        this.renderer = renderer;
        this.pack = pack;
        this.runtime = runtime;
        this.uniforms = uniforms;
        for (var program : pack.programs()) if (program.stage() == CompiledShaderPack.Stage.GBUFFER) programs.put(baseName(program), program);
        constants = renderer.allocator().hostBuffer(BlazePackVertexInputs.constants().remaining(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        owned.add(constants);
        constants.write(BlazePackVertexInputs.constants());
        white = solid(1, 1, 1, 1);
        flatNormal = solid(0.5f, 0.5f, 1, 1);
        noSpecular = solid(0, 0, 0, 0);
        renderer.packScene(this);
    }

    public static String baseName(CompiledShaderPack.Program program) {
        return program.name().substring(program.name().lastIndexOf('/') + 1);
    }

    /** Every program a world draw can reach: a program no draw selects still counts as bound, as nothing asks for it. */
    public static java.util.Set<String> worldPrograms(CompiledShaderPack pack) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var program : pack.programs()) if (program.stage() == CompiledShaderPack.Stage.GBUFFER) names.add(baseName(program));
        return names;
    }

    public void begin(BlazeTarget mainTarget) {
        main = mainTarget;
        open = true;
        opaqueDone = false;
        nextPhase();
    }

    /** The deferred passes ran and may have swapped targets, so everything cached against the old ones is stale. */
    public void afterOpaque() {
        if (!open || opaqueDone) return;
        opaqueDone = true;
        nextPhase();
    }

    public void end() {
        open = false;
    }

    boolean routes(BlazeTarget target) {
        return open && target == main;
    }

    void draw(BlazeMesh mesh, BlazeShaderSnapshot game) {
        String shader = activeShader();
        BlazePackDraw draw = shader == null ? null : select(shader);
        if (draw != null) {
            try {
                draw.prepare(phase, runtime, uniforms);
                renderer.draw(mesh, draw.target(), draw.snapshot(game));
                return;
            } catch (RuntimeException failure) {
                BlazeGlBoundary.reportOnce("pack program " + draw.program.name(), "Pack program " + draw.program.name() + " cannot draw " + shader + ", which keeps the game's shader: " + failure);
                forget(shader);
            }
        }
        renderer.draw(mesh, plain(), game);
    }

    private void nextPhase() {
        phase++;
        plain = null;
    }

    private BlazeTarget plain() {
        if (plain == null) plain = target(Collections.singletonList(runtime.color(0)));
        return plain;
    }

    /** The active program is the source of truth: a mod can draw with its own shader while another one is current. */
    private String activeShader() {
        var current = RenderSystem.getShader();
        return current != null && current.getId() == renderer.programs().activeId() ? current.getName() : null;
    }

    private BlazePackDraw select(String shader) {
        BlazePackDraw draw = byShader.get(shader);
        if (draw != null || byShader.containsKey(shader)) return draw;
        String wanted = BlazePackSelection.program(shader);
        var program = wanted == null ? null : PackProgramFallbacks.resolve(wanted, programs.keySet()).map(programs::get).orElse(null);
        if (program != null) {
            draw = byProgram.get(program);
            if (draw == null) {
                draw = new BlazePackDraw(this, program);
                renderer.shape(draw.code, draw);
                byProgram.put(program, draw);
            }
        }
        byShader.put(shader, draw);
        return draw;
    }

    private void forget(String shader) {
        byShader.put(shader, null);
    }

    BlazeTarget target(List<GpuImageView> colors) {
        BlazeTarget target = targets.get(colors);
        if (target == null) {
            List<GpuImageView> key = Collections.unmodifiableList(new ArrayList<>(colors));
            target = renderer.packTarget(key, main.depthView());
            targets.put(key, target);
        }
        return target;
    }

    BlazeTextures.Sampled white() {
        return white;
    }

    BlazeTextures.Sampled flatNormal() {
        return flatNormal;
    }

    BlazeTextures.Sampled noSpecular() {
        return noSpecular;
    }

    long vertexConstants() {
        return constants.handle();
    }

    private BlazeTextures.Sampled solid(float red, float green, float blue, float alpha) {
        GpuImage image = renderer.allocator().createImage(ImageFormat.RGBA8_UNORM, 1, 1, 1, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        owned.add(image);
        renderer.commands().clearColor(image, 0, 0, red, green, blue, alpha);
        return new BlazeTextures.Sampled(image.view(0, 1, 0, 1), runtime.samplerHandle());
    }

    /** The caller has waited for the device: nothing in flight reads these any more. */
    @Override
    public void close() {
        open = false;
        renderer.packScene(null);
        for (BlazePackDraw draw : byProgram.values()) renderer.forgetProgram(draw.code);
        for (BlazeTarget target : targets.values()) target.close();
        targets.clear();
        for (AutoCloseable resource : owned) {
            try {
                resource.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Pack scene resource failed to close", failure);
            }
        }
        owned.clear();
    }
}
