package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
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
import java.util.function.IntSupplier;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.vulkan.VK10.*;

/** Draws Sodium's compact chunk meshes natively: one indirect multi-draw per region, in device-local buffers. */
public final class BlazeTerrain implements AutoCloseable {
    private static final int UNIFORM_BYTES = 176, PUSH_BYTES = 16, COMMAND_BYTES = 20;
    private static final String VERTEX = """
            #version 450
            layout(location=0) in uvec2 a_Position;
            layout(location=1) in vec4 a_Color;
            layout(location=2) in uvec2 a_TexCoord;
            layout(location=3) in uvec4 a_LightAndData;
            layout(set=0,binding=0,std140) uniform Terrain { mat4 projection; mat4 modelView; vec4 fogColor; vec2 shrink; float fogStart; float fogEnd; int fogShape; };
            layout(push_constant) uniform Region { vec3 regionOffset; };
            layout(set=0,binding=2) uniform sampler2D u_LightTex;
            layout(location=0) out vec4 v_Color;
            layout(location=1) out vec2 v_TexCoord;
            layout(location=2) out float v_Distance;
            layout(location=3) flat out uint v_Material;
            void main() {
                uvec3 hi = (uvec3(a_Position.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
                uvec3 lo = (uvec3(a_Position.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
                vec3 local = vec3((hi << 10u) | lo) * (32.0 / 1048576.0) - 8.0;
                uint draw = a_LightAndData[3];
                vec3 section = vec3((uvec3(draw) >> uvec3(5u, 0u, 2u)) & uvec3(7u, 3u, 7u)) * 16.0;
                vec3 position = local + section + regionOffset;
                v_Distance = fogShape == 1 ? max(length(position.xz), abs(position.y)) : length(position);
                gl_Position = projection * modelView * vec4(position, 1.0);
                gl_Position.z = 0.5 * (gl_Position.w + gl_Position.z);
                v_Color = a_Color * texture(u_LightTex, vec2(a_LightAndData.xy) / 256.0);
                vec2 bias = mix(vec2(-1.0), vec2(1.0), bvec2(a_TexCoord >> 15u));
                v_TexCoord = bias * shrink + vec2(a_TexCoord & 0x7FFFu) / 32768.0;
                v_Material = a_LightAndData[2];
            }
            """;
    private static final String FRAGMENT = """
            #version 450
            layout(set=0,binding=0,std140) uniform Terrain { mat4 projection; mat4 modelView; vec4 fogColor; vec2 shrink; float fogStart; float fogEnd; int fogShape; };
            layout(set=0,binding=1) uniform sampler2D u_BlockTex;
            layout(location=0) in vec4 v_Color;
            layout(location=1) in vec2 v_TexCoord;
            layout(location=2) in float v_Distance;
            layout(location=3) flat in uint v_Material;
            layout(location=0) out vec4 fragColor;
            const float CUTOFF[4] = float[4](0.0, 0.1, 0.1, 1.0);
            void main() {
                vec4 color = (v_Material & 1u) != 0u ? texture(u_BlockTex, v_TexCoord) : textureLod(u_BlockTex, v_TexCoord, 0.0);
                color *= v_Color;
                DISCARD
                if (v_Distance > fogStart) {
                    float factor = v_Distance < fogEnd ? smoothstep(fogStart, fogEnd, v_Distance) : 1.0;
                    color.rgb = mix(color.rgb, fogColor.rgb, factor * fogColor.a);
                }
                fragColor = color;
            }
            """;
    private static final List<NativeGraphicsPipeline.Attribute> ATTRIBUTES = List.of(
            new NativeGraphicsPipeline.Attribute(0, 0, VK_FORMAT_R32G32_UINT, 0),
            new NativeGraphicsPipeline.Attribute(1, 0, VK_FORMAT_R8G8B8A8_UNORM, 8),
            new NativeGraphicsPipeline.Attribute(2, 0, VK_FORMAT_R16G16_UINT, 12),
            new NativeGraphicsPipeline.Attribute(3, 0, VK_FORMAT_R8G8B8A8_UINT, 16));

    private record PipelineKey(BlazeProgramCompiler.Program program, NativeGraphicsPipeline.Depth depth, NativeGraphicsPipeline.Color color,
            NativeGraphicsPipeline.ColorLogic logic, int cull, int polygonMode, long renderPass, int stride) {}

    private final BlazeRenderer owner;
    private final BlazeProgramCompiler.Program solid, cutout;
    private final Map<PipelineKey, NativeGraphicsPipeline> pipelines = new HashMap<>();
    private final float[] projection = new float[16], modelView = new float[16];
    private BlazeTerrainPass pending;
    private BlazeProgramCompiler.Program program;
    private boolean localIndices;
    private int stride;
    private IntSupplier sharedIndices;
    private GpuBuffer vertices, indices;
    private float x, y, z;

    BlazeTerrain(BlazeRenderer owner) {
        this.owner = owner;
        try (var compiler = new ShadercCompiler()) {
            solid = compile(compiler, "");
            cutout = compile(compiler, "if (color.a < CUTOFF[(v_Material >> 1u) & 3u]) discard;");
        }
    }

    private static BlazeProgramCompiler.Program compile(ShadercCompiler compiler, String discard) {
        return new BlazeProgramCompiler.Program(compiler.compile("sodium_terrain.vsh", VERTEX, ShaderStage.VERTEX),
                compiler.compile("sodium_terrain.fsh", FRAGMENT.replace("DISCARD", discard), ShaderStage.FRAGMENT),
                new ProgramBindings(Map.of(), UNIFORM_BYTES, Map.of("u_BlockTex", 1, "u_LightTex", 2), Map.of(), Map.of(), Map.of()));
    }

    /** @param stride the bytes per vertex of the active Sodium format; mods may append fields after the compact layout */
    public void begin(Matrix4fc projection, Matrix4fc modelView, int stride, boolean fragmentDiscard, boolean localIndices, IntSupplier sharedIndices) {
        this.stride = stride;
        projection.get(this.projection);
        modelView.get(this.modelView);
        program = fragmentDiscard ? cutout : solid;
        this.localIndices = localIndices;
        this.sharedIndices = sharedIndices;
    }

    public void region(int geometry, int regionIndices, float x, float y, float z) {
        var buffers = BlazeGlBoundary.buffers();
        vertices = buffers.device(owner, geometry);
        indices = buffers.device(owner, localIndices ? regionIndices : sharedIndices.getAsInt());
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Sodium's batch memory is rewritten next frame, so its parallel arrays become an owned indirect command slice here. */
    public void draw(long elementPointers, long elementCounts, long baseVertices, int count) {
        if (count == 0 || vertices == null || indices == null || owner.drawingNowhere()) return;
        if (pending == null) pending = new BlazeTerrainPass(owner, owner.currentTarget(), owner.drawState().snapshot(), this::uniforms, program, this::pipeline);
        BlazeBufferPool.Slice commands = owner.hostStorage().acquire(count * COMMAND_BYTES);
        try {
            try (var mapping = commands.buffer().mapRange(commands.offset(), commands.size(), false, true)) {
                ByteBuffer target = mapping.buffer();
                for (int index = 0; index < count; index++) {
                    target.putInt(MemoryUtil.memGetInt(elementCounts + index * 4L));
                    target.putInt(1);
                    target.putInt((int) (MemoryUtil.memGetAddress(elementPointers + index * (long) Long.BYTES) >>> 2));
                    target.putInt(MemoryUtil.memGetInt(baseVertices + index * 4L));
                    target.putInt(0);
                }
            }
            pending.add(new BlazeTerrainPass.Region(vertices, indices, x, y, z, commands, count));
        } catch (RuntimeException | Error failure) {
            commands.close();
            throw failure;
        }
    }

    public void end() { flush(); }

    /** Hands the captured draws to the queue, which also keeps any buffer retired after this call alive past them. */
    void flush() {
        if (pending == null) return;
        BlazeTerrainPass pass = pending;
        pending = null;
        owner.enqueue(pass);
    }

    private void uniforms(ByteBuffer target, float atlasWidth, float atlasHeight) {
        for (float value : projection) target.putFloat(value);
        for (float value : modelView) target.putFloat(value);
        float[] fog = RenderSystem.getShaderFogColor();
        for (int index = 0; index < 4; index++) target.putFloat(fog[index]);
        // Sodium shrinks texture coordinates by the sub-texel precision of the atlas, here 8 bits.
        double offset = 3.0517578E-5F;
        target.putFloat((float) (offset - 1.0 / atlasWidth / 256.0)).putFloat((float) (offset - 1.0 / atlasHeight / 256.0));
        target.putFloat(RenderSystem.getShaderFogStart()).putFloat(RenderSystem.getShaderFogEnd());
        target.putInt(RenderSystem.getShaderFogShape().getIndex());
    }

    private NativeGraphicsPipeline pipeline(BlazeProgramCompiler.Program program, BlazeDrawState.Snapshot draw, BlazeTarget target, long descriptorLayout) {
        var key = new PipelineKey(program, draw.depth(), draw.color(), draw.logic(), draw.cull(), draw.polygonMode(), target.renderPass(), stride);
        var existing = pipelines.get(key);
        if (existing != null) return existing;
        var description = new NativeGraphicsPipeline.Description(List.of(
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_VERTEX_BIT, "main", program.vertex().directBuffer()),
                new NativeGraphicsPipeline.Shader(VK_SHADER_STAGE_FRAGMENT_BIT, "main", program.fragment().directBuffer())),
                List.of(new NativeGraphicsPipeline.Binding(0, stride, 0)), ATTRIBUTES, new long[]{descriptorLayout}, PUSH_BYTES,
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
