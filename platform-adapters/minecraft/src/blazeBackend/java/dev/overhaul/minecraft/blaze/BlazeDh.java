package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.shader.compile.ShaderStage;
import dev.overhaul.shader.compile.ShadercCompiler;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Draws Distant Horizons' LOD buffers on Vulkan and puts the result on the frame; the mod keeps its section lists, uploads and targets. */
public final class BlazeDh implements AutoCloseable {
    private static final int TERRAIN_BYTES = 96, COMPOSITE_BYTES = 160, PUSH_BYTES = 16, VERTEX_BYTES = 16;
    private static final int WRITE_RGBA = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    private static final List<NativeGraphicsPipeline.Attribute> ATTRIBUTES = List.of(
            new NativeGraphicsPipeline.Attribute(0, 0, VK_FORMAT_R16G16B16A16_UINT, 0),
            new NativeGraphicsPipeline.Attribute(1, 0, VK_FORMAT_R8G8B8A8_UNORM, 8),
            new NativeGraphicsPipeline.Attribute(2, 0, VK_FORMAT_R8G8B8A8_UINT, 12));

    public record TerrainSettings(float[] combined, float microOffset, float earthRadius, float worldYOffset, float clipDistance,
            int noiseSteps, float noiseIntensity, int noiseDropoff, int flags) {}

    public record FogSettings(float[] color, float scale, float verticalScale, int falloff, float farStart, float farLength, float farMin, float farRange,
            float farDensity, float heightStart, float heightLength, int heightFalloff, int mixMode, float heightBase, float cameraY, int flags) {}

    private record PipelineKey(BlazeProgramCompiler.Program program, NativeGraphicsPipeline.Depth depth, NativeGraphicsPipeline.Color color,
            NativeGraphicsPipeline.ColorLogic logic, int cull, int polygonMode, long renderPass) {}

    private record Lod(GpuBuffer vertices, GpuBuffer indices, float x, float y, float z, int indexCount) implements BlazeTerrainPass.Draw {
        @Override public void record(VkCommandBuffer command, long pipelineLayout, boolean multiDraw) {
            BlazeTerrainPass.bindGeometry(command, pipelineLayout, vertices, indices, x, y, z);
            vkCmdDrawIndexed(command, indexCount, 1, 0, 0, 0);
        }
    }

    private final BlazeRenderer owner;
    private final BlazeProgramCompiler.Program terrain, composite;
    private final Map<PipelineKey, NativeGraphicsPipeline> pipelines = new HashMap<>();
    private BlazeTerrainPass pending;
    private TerrainSettings settings;
    private FogSettings fog;

    BlazeDh(BlazeRenderer owner) {
        this.owner = owner;
        try (var compiler = new ShadercCompiler()) {
            terrain = new BlazeProgramCompiler.Program(compiler.compile("dh_terrain.vsh", BlazeDhShaders.TERRAIN_VERTEX, ShaderStage.VERTEX),
                    compiler.compile("dh_terrain.fsh", BlazeDhShaders.TERRAIN_FRAGMENT, ShaderStage.FRAGMENT),
                    new ProgramBindings(Map.of(), TERRAIN_BYTES, Map.of("uLightMap", 1, "uBlockAtlas", 2), Map.of(), Map.of(), Map.of()));
            composite = new BlazeProgramCompiler.Program(compiler.compile("dh_composite.vsh", BlazeDhShaders.COMPOSITE_VERTEX, ShaderStage.VERTEX),
                    compiler.compile("dh_composite.fsh", BlazeDhShaders.COMPOSITE_FRAGMENT, ShaderStage.FRAGMENT),
                    new ProgramBindings(Map.of(), COMPOSITE_BYTES, Map.of("uDhColor", 1, "uDhDepth", 2), Map.of(), Map.of(), Map.of()));
        }
    }

    public void begin(TerrainSettings settings) { this.settings = settings; }

    /** @param vertices the GL buffer name DH uploaded this LOD to */
    public void draw(int vertices, int indices, int indexCount, float x, float y, float z) {
        if (owner.drawingNowhere()) return;
        var buffers = BlazeGlBoundary.buffers();
        GpuBuffer vertexBuffer = buffers.device(owner, vertices), indexBuffer = buffers.device(owner, indices);
        if (vertexBuffer == null || indexBuffer == null) return;
        // DH's setup leaves the lightmap on unit 0 and its block atlas on unit 1.
        if (pending == null) pending = new BlazeTerrainPass(owner, owner.currentTarget(), owner.drawState().snapshot(), this::terrainUniforms, terrain, this::pipeline,
                name -> owner.textures().resolveBound(name.equals("uLightMap") ? 0 : 1), "uBlockAtlas");
        pending.add(new Lod(vertexBuffer, indexBuffer, x, y, z, indexCount));
    }

    public void end() { flush(); }

    /** Hands the captured draws to the queue, which also keeps any buffer retired after this call alive past them. */
    void flush() {
        if (pending == null) return;
        BlazeTerrainPass pass = pending;
        pending = null;
        owner.enqueue(pass);
    }

    /** Fog is optional per frame in DH, so its absence is what the composite reads when the fog pass never ran. */
    public void fog(FogSettings fog) { this.fog = fog; }

    /** Blends the LOD colour over the bound frame, with DH's distance fog and far fade, wherever LODs were drawn. */
    public void composite(int colorTexture, int depthTexture, float[] inverseMvmProjection, float fadeStart, float fadeEnd) {
        BlazeTarget target = owner.currentTarget();
        if (target == null || colorTexture <= 0 || depthTexture <= 0) return;
        FogSettings frameFog = fog;
        fog = null;
        var state = new BlazeDrawState.Snapshot(new NativeGraphicsPipeline.Depth(false, false, VK_COMPARE_OP_ALWAYS, 0, 0),
                new NativeGraphicsPipeline.Color(true, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD,
                        VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE, VK_BLEND_OP_ADD, WRITE_RGBA),
                NativeGraphicsPipeline.ColorLogic.disabled(), VK_CULL_MODE_NONE, VK_POLYGON_MODE_FILL, 1,
                0, 0, target.width(), target.height(), false, 0, 0, 0, 0);
        var textures = owner.textures();
        var pass = new BlazeTerrainPass(owner, target, state, (bytes, width, height) -> compositeUniforms(bytes, inverseMvmProjection, frameFog, fadeStart, fadeEnd),
                composite, this::pipeline, name -> textures.resolve(name.equals("uDhColor") ? colorTexture : depthTexture), "uDhColor");
        pass.add((command, layout, multiDraw) -> vkCmdDraw(command, 3, 1, 0, 0));
        owner.enqueue(pass);
    }

    private void terrainUniforms(ByteBuffer target, float atlasWidth, float atlasHeight) {
        TerrainSettings values = settings;
        for (float value : values.combined()) target.putFloat(value);
        target.putFloat(values.microOffset()).putFloat(values.earthRadius()).putFloat(values.worldYOffset()).putFloat(values.clipDistance());
        target.putInt(values.noiseSteps()).putFloat(values.noiseIntensity()).putInt(values.noiseDropoff()).putInt(values.flags());
    }

    private static void compositeUniforms(ByteBuffer target, float[] inverse, FogSettings fog, float fadeStart, float fadeEnd) {
        for (float value : inverse) target.putFloat(value);
        if (fog == null) {
            target.position(target.position() + 64);
            target.putFloat(fadeStart).putFloat(fadeEnd).putInt(0);
            return;
        }
        for (float value : fog.color()) target.putFloat(value);
        target.putFloat(fog.scale()).putFloat(fog.verticalScale()).putInt(fog.falloff()).putFloat(fog.farStart());
        target.putFloat(fog.farLength()).putFloat(fog.farMin()).putFloat(fog.farRange()).putFloat(fog.farDensity());
        target.putFloat(fog.heightStart()).putFloat(fog.heightLength()).putInt(fog.heightFalloff()).putInt(fog.mixMode());
        target.putFloat(fog.heightBase()).putFloat(fog.cameraY()).putFloat(fadeStart).putFloat(fadeEnd);
        target.putInt(fog.flags());
    }

    private NativeGraphicsPipeline pipeline(BlazeProgramCompiler.Program program, BlazeDrawState.Snapshot draw, BlazeTarget target, long descriptorLayout) {
        var key = new PipelineKey(program, draw.depth(), draw.color(), draw.logic(), draw.cull(), draw.polygonMode(), target.renderPass());
        var existing = pipelines.get(key);
        if (existing != null) return existing;
        boolean lod = program == terrain;
        var description = new NativeGraphicsPipeline.Description(List.of(
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT, "main", program.vertex().directBuffer()),
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT, "main", program.fragment().directBuffer())),
                lod ? List.of(new NativeGraphicsPipeline.Binding(0, VERTEX_BYTES, 0)) : List.of(), lod ? ATTRIBUTES : List.of(), new long[]{descriptorLayout}, PUSH_BYTES,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, draw.polygonMode(), draw.cull(), VK_FRONT_FACE_CLOCKWISE, draw.depth(),
                List.of(draw.color()), true, 0, 0, draw.logic());
        var created = new NativeGraphicsPipeline(owner.context(), target.renderPass(), description);
        pipelines.put(key, created);
        return created;
    }

    @Override public void close() {
        if (pending != null) { pending.close(); pending = null; }
        for (NativeGraphicsPipeline pipeline : new ArrayList<>(pipelines.values())) pipeline.close();
        pipelines.clear();
    }
}
