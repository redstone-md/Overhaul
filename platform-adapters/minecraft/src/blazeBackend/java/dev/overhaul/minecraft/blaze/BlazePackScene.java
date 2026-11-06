package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.shader.pack.CompiledShaderPack;
import dev.overhaul.shader.pack.PackDhPrograms;
import dev.overhaul.shader.pack.PackIdMap;
import dev.overhaul.shader.pack.PackProgramFallbacks;
import dev.overhaul.shader.pack.PackSectionOffsets;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import dev.overhaul.vulkan.PackGraphRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Sends the game's world draws through a pack's gbuffer programs while its frame is open. Only draws aimed at the main
 * framebuffer move: a mod's own framebuffer keeps its draws. The pack no longer imports the main colour, so a draw with
 * no pack program lands in its first colour target with the game's own shader.
 */
public final class BlazePackScene implements AutoCloseable {
    private static final NativeGraphicsPipeline.Depth SHADOW_DEPTH = new NativeGraphicsPipeline.Depth(true, true, VK_COMPARE_OP_LESS_OR_EQUAL, 0, 0);
    private static final NativeGraphicsPipeline.Color SHADOW_COLOR = new NativeGraphicsPipeline.Color(false, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ZERO, VK_BLEND_OP_ADD, 15);
    private static final NativeGraphicsPipeline.ColorLogic SHADOW_LOGIC = new NativeGraphicsPipeline.ColorLogic(false, VK_LOGIC_OP_COPY);
    /** Rain and snow draw with the particle shader; while the game draws weather that shader goes by this name. */
    static final String WEATHER = "overhaul_weather";
    private record TargetKey(List<GpuImageView> colors, GpuImageView depth) {}
    /** A batched terrain layer's pack side; {@code failed} sends the program back to the section-by-section path. */
    record Sections(BlazeShaderSnapshot shader, BlazeTarget target, Runnable failed) {}
    private final BlazeRenderer renderer;
    private final CompiledShaderPack pack;
    private final PackGraphRuntime runtime;
    private final PackGraphRuntime.Uniforms uniforms;
    private final Map<String, CompiledShaderPack.Program> programs = new HashMap<>(), shadowPrograms = new HashMap<>();
    /** One table per route, so a draw looks its shader up without building a key string. */
    private final Map<String, BlazePackDraw> byShader = new HashMap<>(), byHandShader = new HashMap<>(), bySkyShader = new HashMap<>();
    /** Keyed by identity: a program record hashes every field, and the pack's programs are never copied. */
    private final Map<CompiledShaderPack.Program, BlazePackDraw> byProgram = new IdentityHashMap<>(), sectioned = new IdentityHashMap<>();
    private final Map<TargetKey, BlazeTarget> targets = new HashMap<>();
    private final Map<String, Object> shadowTextures = new HashMap<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
    private final List<BlazePass> onTop = new ArrayList<>();
    private final GpuBuffer constants;
    private final BlazeTextures.Sampled white, flatNormal, noSpecular;
    private final BlazePackDistant distant;
    private final CompiledShaderPack.Program distantOpaque, distantTranslucent;
    private final Map<String, Object> distantTextures = new HashMap<>();
    private final Matrix4f distantView = new Matrix4f(), distantProjection = new Matrix4f();
    private Runnable heldDistant;
    private BlazeTarget main, plain;
    private BlazePackDraw caster;
    private BlazeDrawState.Snapshot shadowRaster;
    private ShadercCompiler compiler;
    private int phase;
    private boolean open, opaqueDone, weather, hand, sky;

    public BlazePackScene(BlazeRenderer renderer, CompiledShaderPack pack, PackGraphRuntime runtime, PackGraphRuntime.Uniforms uniforms) {
        this.renderer = renderer;
        this.pack = pack;
        this.runtime = runtime;
        this.uniforms = uniforms;
        for (var program : pack.programs()) {
            if (program.stage() == CompiledShaderPack.Stage.GBUFFER) programs.put(baseName(program), program);
            if (program.stage() == CompiledShaderPack.Stage.SHADOW) shadowPrograms.put(baseName(program), program);
        }
        constants = renderer.allocator().hostBuffer(BlazePackVertexInputs.constants().remaining(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        owned.add(constants);
        constants.write(BlazePackVertexInputs.constants());
        white = solid(1, 1, 1, 1);
        flatNormal = solid(0.5f, 0.5f, 1, 1);
        noSpecular = solid(0, 0, 0, 0);
        distantOpaque = programs.get("dh_terrain");
        distantTranslucent = distantOpaque == null ? null : programs.getOrDefault("dh_water", distantOpaque);
        GpuImage size = runtime.color(0).image();
        distant = distantOpaque == null ? null : new BlazePackDistant(renderer, size.width(0), size.height(0));
        if (distant != null) owned.add(distant);
        renderer.packScene(this);
    }

    PackIdMap ids() {
        return pack.ids();
    }

    public static String baseName(CompiledShaderPack.Program program) {
        return program.name().substring(program.name().lastIndexOf('/') + 1);
    }

    /** True when the pack has a program this class has to draw for: without one the game draws as it would anyway. */
    public static boolean drawsWorld(CompiledShaderPack pack) {
        for (var program : pack.programs()) if (program.stage() == CompiledShaderPack.Stage.GBUFFER || program.stage() == CompiledShaderPack.Stage.SHADOW) return true;
        return false;
    }

    /** Every program a world draw can reach: a program no draw selects still counts as bound, as nothing asks for it. */
    public static java.util.Set<String> worldPrograms(CompiledShaderPack pack) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var program : pack.programs()) if (program.stage() == CompiledShaderPack.Stage.GBUFFER || program.stage() == CompiledShaderPack.Stage.SHADOW) names.add(baseName(program));
        return names;
    }

    public void begin(BlazeTarget mainTarget) {
        main = mainTarget;
        open = true;
        opaqueDone = false;
        heldDistant = null;
        nextPhase();
        if (distant != null) distant.begin(target(List.of(), distant.view(0)), target(List.of(), distant.view(1)));
    }

    /** The camera the frame's LODs draw with: the pack's model-view and its projection over DH's clip planes. */
    public void distantCamera(Matrix4fc modelView, Matrix4fc projection) {
        distantView.set(modelView);
        distantProjection.set(projection);
    }

    /** True while the pack draws Distant Horizons' terrain itself, so DH's own composite has nothing to add. */
    public boolean drawsDistant() {
        return open && distant != null;
    }

    /** LOD depth for the pack's composites; empty when the pack draws no LODs. */
    public Map<String, GpuImageView> distantTextures() {
        return distant == null ? Map.of() : distant.textures();
    }

    GpuImageView distantDepth(int index) {
        return distant == null ? null : distant.view(index);
    }

    /**
     * DH draws translucent LODs before the pack's deferred passes, which would light them twice: such a pass is kept
     * and drawn right after those passes, as the pack's own translucent terrain is. True when it was kept.
     */
    boolean holdDistant(Runnable later) {
        if (!drawsDistant() || opaqueDone) return false;
        heldDistant = later;
        return true;
    }

    /** One DH terrain pass as a batch, each LOD added with its offset from the camera; null draws nothing. */
    BlazeSectionBatch distant(boolean opaque) {
        if (!drawsDistant()) return null;
        if (!opaque && !opaqueDone) {
            BlazeGlBoundary.reportOnce("pack lods early", "Translucent LODs came before the pack's deferred passes and were left out");
            return null;
        }
        var program = opaque ? distantOpaque : distantTranslucent;
        BlazePackDraw draw = sectioned.get(program);
        if (draw == null && sectioned.containsKey(program)) return null;
        try {
            if (draw == null) {
                draw = new BlazePackDraw(this, program, program.code(), true);
                renderer.shape(draw.code, draw);
                sectioned.put(program, draw);
            }
            draw.prepare(phase, runtime, uniforms);
            distantTextures.put("Sampler2", RenderSystem.getShaderTexture(2));
            return renderer.sectionBatch().begin(draw.cast(distantView, distantProjection, distantTextures), null, !opaque, draw.target(),
                    distant.raster(opaque), () -> sectioned.put(program, null));
        } catch (RuntimeException failure) {
            BlazeGlBoundary.reportOnce("pack lods " + program.name(), "Pack program " + program.name() + " cannot draw LODs, which are left out: " + failure);
            sectioned.put(program, null);
            return null;
        }
    }

    /** Ends a pass {@link #distant} began; after the opaque one the depth translucent LODs sample is copied. */
    void distantDone(BlazeSectionBatch batch, boolean opaque) {
        batch.end();
        if (opaque) distant.opaqueDone();
    }

    /** The depth a scene program tests against: LOD programs have their own. */
    GpuImageView depthFor(CompiledShaderPack.Program program) {
        return distant != null && PackDhPrograms.drawn(baseName(program)) ? distant.view(0) : main.depthView();
    }

    /** The deferred passes ran and may have swapped targets, so everything cached against the old ones is stale. */
    public void afterOpaque() {
        if (!open || opaqueDone) return;
        opaqueDone = true;
        nextPhase();
        Runnable held = heldDistant;
        heldDistant = null;
        if (held != null) held.run();
    }

    public void end() {
        open = false;
        heldDistant = null;
        weather = false;
        hand = false;
        sky = false;
    }

    /** Queues the draws held back while the frame was open, over the image the pack's last pass left in the main target. */
    public void drawOnTop() {
        try {
            for (BlazePass pass : onTop) renderer.queue(pass);
        } finally {
            onTop.clear();
        }
    }

    /** Brackets the game's rain and snow, which the pack draws with gbuffers_weather. */
    public void weather(boolean drawing) {
        weather = drawing;
    }

    /** Brackets the game's sky, which the pack draws with gbuffers_skybasic and gbuffers_skytextured. */
    public void sky(boolean drawing) {
        sky = drawing;
    }

    /** Brackets the first-person hand, which the pack draws with gbuffers_hand and gbuffers_hand_water. */
    public void hand(boolean drawing) {
        hand = drawing;
    }

    public boolean castsShadows() {
        return !shadowPrograms.isEmpty();
    }

    /** Light-camera draws of one frame use these textures and fill the whole map, whatever the game's state says. */
    public void beginShadows(int atlas, int lightmap, int resolution) {
        shadowTextures.put("Sampler0", atlas);
        shadowTextures.put("Sampler2", lightmap);
        shadowRaster = new BlazeDrawState.Snapshot(SHADOW_DEPTH, SHADOW_COLOR, SHADOW_LOGIC, VK_CULL_MODE_NONE, VK_POLYGON_MODE_FILL, 1, 0, 0, resolution, resolution, false, 0, 0, 0, 0);
    }

    /** Selects the program for the next casters; a pack without one for this layer leaves it out of the shadow map. */
    public void shadowLayer(String wanted) {
        var program = PackProgramFallbacks.resolve(wanted, shadowPrograms.keySet()).map(shadowPrograms::get).orElse(null);
        caster = program == null ? null : draw(program);
        if (caster != null) caster.prepare(phase, runtime, uniforms);
    }

    /** The light camera's casters of this layer as one batch, each added with its offset from the camera; null casts them one by one. */
    public BlazeSectionBatch shadowSections(Matrix4fc view, Matrix4fc projection) {
        if (caster == null || !BlazeOptimization.SECTION_BATCH.on() || !BlazeOptimization.PACK_SECTION_BATCH.on() || !renderer.context().indirectFirstInstance()) return null;
        BlazePackDraw draw = sectioned(caster);
        if (draw == null) return null;
        try {
            draw.prepare(phase, runtime, uniforms);
            return renderer.sectionBatch().begin(draw.cast(view, projection, shadowTextures), null, false, draw.target(), shadowRaster, () -> sectioned.put(draw.program, null));
        } catch (RuntimeException failure) {
            BlazeGlBoundary.reportOnce("pack sections " + draw.program.name(), "Pack program " + draw.program.name() + " casts shadows section by section: " + failure);
            sectioned.put(draw.program, null);
            return null;
        }
    }

    public void castShadow(BlazeMesh mesh, Matrix4fc modelView, Matrix4fc projection) {
        if (caster != null) renderer.draw(mesh, caster.target(), caster.cast(modelView, projection, shadowTextures), shadowRaster);
    }

    /** The shadow composite passes ran and may have flipped shadow colours, which gbuffer programs then sample. */
    public void shadowsDone() {
        nextPhase();
    }

    boolean routes(BlazeTarget target) {
        return open && !programs.isEmpty() && target == main;
    }

    void draw(BlazeMesh mesh, BlazeShaderSnapshot game) {
        String shader = activeShader();
        if (weather && "particle".equals(shader)) shader = WEATHER;
        if (shader != null && BlazePackSelection.onTop(shader)) {
            BlazePass pass = renderer.capture(mesh, main, game);
            if (pass != null) onTop.add(pass);
            return;
        }
        BlazePackDraw draw = shader == null ? null : select(shader);
        if (draw != null) {
            try {
                draw.prepare(phase, runtime, uniforms);
                renderer.draw(mesh, draw.target(), draw.snapshot(game, BlazePackSelection.alphaCutoff(shader)));
                return;
            } catch (RuntimeException failure) {
                BlazeGlBoundary.reportOnce("pack program " + draw.program.name(), "Pack program " + draw.program.name() + " cannot draw " + shader + ", which keeps the game's shader: " + failure);
                forget(shader);
            }
        }
        renderer.draw(mesh, plain(), game);
    }

    /** Whether the terrain shader now active can draw its layer in one batch; the first ask compiles the variant. */
    boolean batchesSections() {
        return sectionDraw() != null;
    }

    /** Null keeps the layer on the section-by-section path. */
    Sections sections(BlazeShaderSnapshot game) {
        BlazePackDraw draw = sectionDraw();
        if (draw == null) return null;
        String shader = RenderSystem.getShader().getName();
        try {
            draw.prepare(phase, runtime, uniforms);
            return new Sections(draw.snapshot(game, BlazePackSelection.alphaCutoff(shader)), draw.target(), () -> sectioned.put(draw.program, null));
        } catch (RuntimeException failure) {
            BlazeGlBoundary.reportOnce("pack sections " + draw.program.name(), "Pack program " + draw.program.name() + " draws terrain section by section: " + failure);
            sectioned.put(draw.program, null);
            return null;
        }
    }

    private BlazePackDraw sectionDraw() {
        if (hand || weather || sky || !BlazeOptimization.PACK_SECTION_BATCH.on()) return null;
        // Asked before the layer binds its program, so the terrain shader itself names the draw.
        var current = RenderSystem.getShader();
        String shader = current == null ? null : current.getName();
        if (shader == null || BlazePackSelection.onTop(shader)) return null;
        BlazePackDraw draw = select(shader);
        return draw == null ? null : sectioned(draw);
    }

    /** The variant of a program that reads section offsets per instance, built once; null when the pack's code has no place for it. */
    private BlazePackDraw sectioned(BlazePackDraw draw) {
        if (sectioned.containsKey(draw.program)) return sectioned.get(draw.program);
        BlazePackDraw batched = null;
        try {
            if (compiler == null) compiler = new ShadercCompiler();
            var code = PackSectionOffsets.compile(pack, draw.program, compiler);
            if (code != null) {
                batched = new BlazePackDraw(this, draw.program, code, true);
                renderer.shape(batched.code, batched);
            }
        } catch (RuntimeException failure) {
            BlazeGlBoundary.reportOnce("pack sections " + draw.program.name(), "Pack program " + draw.program.name() + " draws terrain section by section: " + failure);
        }
        sectioned.put(draw.program, batched);
        return batched;
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

    /** Draws in a bracket pick their program by bracket, so the same game shader keeps one cache entry per bracket. */
    private Map<String, BlazePackDraw> routeTable() {
        return hand ? byHandShader : sky ? bySkyShader : byShader;
    }

    private BlazePackDraw select(String shader) {
        Map<String, BlazePackDraw> table = routeTable();
        BlazePackDraw draw = table.get(shader);
        if (draw != null || table.containsKey(shader)) return draw;
        String wanted = hand ? BlazePackSelection.handProgram(shader) : sky ? BlazePackSelection.skyProgram(shader) : BlazePackSelection.program(shader);
        var program = wanted == null ? null : PackProgramFallbacks.resolve(wanted, programs.keySet()).map(programs::get).orElse(null);
        if (program != null) draw = draw(program);
        table.put(shader, draw);
        return draw;
    }

    private BlazePackDraw draw(CompiledShaderPack.Program program) {
        BlazePackDraw draw = byProgram.get(program);
        if (draw == null) {
            draw = new BlazePackDraw(this, program);
            renderer.shape(draw.code, draw);
            byProgram.put(program, draw);
        }
        return draw;
    }

    private void forget(String shader) {
        routeTable().put(shader, null);
    }

    BlazeTarget target(List<GpuImageView> colors) {
        return target(colors, main.depthView());
    }

    BlazeTarget target(List<GpuImageView> colors, GpuImageView depth) {
        TargetKey key = new TargetKey(Collections.unmodifiableList(new ArrayList<>(colors)), depth);
        BlazeTarget target = targets.get(key);
        if (target == null) {
            target = renderer.packTarget(key.colors(), depth);
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
        for (BlazePass pass : onTop) pass.close();
        onTop.clear();
        renderer.packScene(null);
        for (BlazePackDraw draw : byProgram.values()) renderer.forgetProgram(draw.code);
        for (BlazePackDraw draw : sectioned.values()) if (draw != null) renderer.forgetProgram(draw.code);
        sectioned.clear();
        if (compiler != null) compiler.close();
        compiler = null;
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
