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
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Draws Distant Horizons' LOD buffers on Vulkan and puts the result on the frame; the mod keeps its section lists, uploads and targets. */
public final class BlazeDh implements AutoCloseable {
    private static final int TERRAIN_BYTES = 96, COMPOSITE_BYTES = 160, BOX_BYTES = 64, PUSH_BYTES = 16, BOX_PUSH_BYTES = 56, VERTEX_BYTES = 16, BOX_INDICES = 36;
    private static final int WRITE_RGBA = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    private static final List<NativeGraphicsPipeline.Attribute> ATTRIBUTES = List.of(
            new NativeGraphicsPipeline.Attribute(0, 0, VK_FORMAT_R16G16B16A16_UINT, 0),
            new NativeGraphicsPipeline.Attribute(1, 0, VK_FORMAT_R8G8B8A8_UNORM, 8),
            new NativeGraphicsPipeline.Attribute(2, 0, VK_FORMAT_R8G8B8A8_UINT, 12));

    private static final List<NativeGraphicsPipeline.Binding> BOX_BINDINGS = List.of(
            new NativeGraphicsPipeline.Binding(0, 12, 0), new NativeGraphicsPipeline.Binding(1, 16, 1), new NativeGraphicsPipeline.Binding(2, 12, 1),
            new NativeGraphicsPipeline.Binding(3, 12, 1), new NativeGraphicsPipeline.Binding(4, 12, 1));
    private static final List<NativeGraphicsPipeline.Attribute> BOX_ATTRIBUTES = List.of(
            new NativeGraphicsPipeline.Attribute(0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0),
            new NativeGraphicsPipeline.Attribute(1, 1, VK_FORMAT_R32G32B32A32_SFLOAT, 0),
            new NativeGraphicsPipeline.Attribute(2, 2, VK_FORMAT_R32G32B32_SFLOAT, 0),
            new NativeGraphicsPipeline.Attribute(3, 3, VK_FORMAT_R32G32B32_SINT, 0),
            new NativeGraphicsPipeline.Attribute(4, 4, VK_FORMAT_R32G32B32_SFLOAT, 0));

    /**
     * One group of instanced boxes, by the GL names DH uploaded them to.
     * @param chunkOffset the group's chunk relative to the camera's; with {@code subOffset} it stays exact far from the origin
     * @param shading north, south, east, west, top, bottom
     */
    public record BoxGroup(int colors, int scales, int chunks, int subChunks, int count, int[] chunkOffset, float[] subOffset, int skyLight, int blockLight, float[] shading) {}

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

    private record BoxDraw(GpuBuffer vertices, GpuBuffer indices, GpuBuffer colors, GpuBuffer scales, GpuBuffer chunks, GpuBuffer subChunks, BoxGroup group) implements BlazeTerrainPass.Draw {
        @Override public void record(VkCommandBuffer command, long pipelineLayout, boolean multiDraw) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer push = stack.malloc(BOX_PUSH_BYTES);
                int[] chunk = group.chunkOffset();
                float[] sub = group.subOffset();
                push.putInt(chunk[0]).putInt(chunk[1]).putInt(chunk[2]).putInt(group.skyLight());
                push.putFloat(sub[0]).putFloat(sub[1]).putFloat(sub[2]).putInt(group.blockLight());
                for (float shade : group.shading()) push.putFloat(shade);
                push.flip();
                vkCmdPushConstants(command, pipelineLayout, BlazeTerrainPass.PUSH_STAGES, 0, push);
                vkCmdBindVertexBuffers(command, 0, stack.longs(vertices.handle(), colors.handle(), scales.handle(), chunks.handle(), subChunks.handle()), stack.longs(0, 0, 0, 0, 0));
                vkCmdBindIndexBuffer(command, indices.handle(), 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexed(command, BOX_INDICES, group.count(), 0, 0, 0);
            }
        }
    }

    private final BlazeRenderer owner;
    private final BlazeProgramCompiler.Program terrain, boxes, composite;
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
            boxes = new BlazeProgramCompiler.Program(compiler.compile("dh_boxes.vsh", BlazeDhShaders.BOX_VERTEX, ShaderStage.VERTEX),
                    compiler.compile("dh_boxes.fsh", BlazeDhShaders.BOX_FRAGMENT, ShaderStage.FRAGMENT),
                    new ProgramBindings(Map.of(), BOX_BYTES, Map.of("uLightMap", 1), Map.of(), Map.of(), Map.of()));
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

    /** Instanced boxes in the bound framebuffer. DH's setup leaves the lightmap on unit 0. */
    public void drawBoxes(float[] combined, int vertices, int indices, List<BoxGroup> groups) {
        if (groups.isEmpty() || owner.drawingNowhere()) return;
        var buffers = BlazeGlBoundary.buffers();
        GpuBuffer vertexBuffer = buffers.device(owner, vertices), indexBuffer = buffers.device(owner, indices);
        if (vertexBuffer == null || indexBuffer == null) return;
        BlazeTerrainPass pass = null;
        for (BoxGroup group : groups) {
            GpuBuffer colors = buffers.device(owner, group.colors()), scales = buffers.device(owner, group.scales());
            GpuBuffer chunks = buffers.device(owner, group.chunks()), subChunks = buffers.device(owner, group.subChunks());
            if (colors == null || scales == null || chunks == null || subChunks == null) continue;
            if (pass == null) pass = new BlazeTerrainPass(owner, owner.currentTarget(), owner.drawState().snapshot(), (bytes, width, height) -> { for (float value : combined) bytes.putFloat(value); },
                    boxes, this::pipeline, name -> owner.textures().resolveBound(0), "uLightMap");
            pass.add(new BoxDraw(vertexBuffer, indexBuffer, colors, scales, chunks, subChunks, group));
        }
        if (pass != null) owner.enqueue(pass);
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
        var description = new NativeGraphicsPipeline.Description(List.of(
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT, "main", program.vertex().directBuffer()),
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT, "main", program.fragment().directBuffer())),
                program == terrain ? List.of(new NativeGraphicsPipeline.Binding(0, VERTEX_BYTES, 0)) : program == boxes ? BOX_BINDINGS : List.of(),
                program == terrain ? ATTRIBUTES : program == boxes ? BOX_ATTRIBUTES : List.of(), new long[]{descriptorLayout}, program == boxes ? BOX_PUSH_BYTES : PUSH_BYTES,
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
