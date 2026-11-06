package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Terrain vertices in 20 bytes instead of 32. Positions keep 1/2048 block over [-8, 24) and texture coordinates 1/32768
 * under 2, both dyadic so a seam shared by two sections stays shared. A mesh outside those ranges stays in its full
 * layout, which the terrain path draws just the same. The shader's own source reads the new attributes through macros,
 * so resource packs that replace terrain shaders keep working.
 */
public final class BlazeCompactSection {
    static final String VERTEX_ATTRIBUTE = "OverhaulCompactVertex";
    private static final String UV_ATTRIBUTE = "OverhaulCompactUv";
    private static final int FULL_STRIDE = 32, STRIDE = 20;
    private static final float POSITION_BIAS = 8f, POSITION_SCALE = 2048f, UV_SCALE = 32768f;
    static final BlazeMeshState.Layout LAYOUT = new BlazeMeshState.Layout(STRIDE, List.of(
            new BlazeMeshState.Field(VERTEX_ATTRIBUTE, 0, VK_FORMAT_R16G16B16A16_UINT),
            new BlazeMeshState.Field("Color", 8, VK_FORMAT_R8G8B8A8_UNORM),
            new BlazeMeshState.Field(UV_ATTRIBUTE, 12, VK_FORMAT_R16G16_UINT),
            new BlazeMeshState.Field("Normal", 16, VK_FORMAT_R8G8B8_SNORM))).canonical();
    private static final Set<String> READABLE = Set.of(VERTEX_ATTRIBUTE, "Color", UV_ATTRIBUTE, "Normal", BlazeSectionShape.OFFSET_ATTRIBUTE);
    private static final Pattern POSITION = Pattern.compile("\\bin\\s+vec3\\s+Position\\s*;"),
            UV0 = Pattern.compile("\\bin\\s+vec2\\s+UV0\\s*;"),
            UV2 = Pattern.compile("\\bin\\s+ivec2\\s+UV2\\s*;");

    private BlazeCompactSection() {}

    /** Uploads this terrain mesh compacted, or does nothing and answers false when it does not fit. */
    public static boolean upload(BlazeMesh mesh, BlazeMeshState state, ByteBuffer vertices, ByteBuffer indices) {
        if (!fullLayout(state.layout()) || vertices.remaining() != state.vertexCount() * FULL_STRIDE) {
            BlazeStats.sectionMeshLeftFull();
            return false;
        }
        ByteBuffer compact = MemoryUtil.memAlloc(Math.max(1, state.vertexCount() * STRIDE));
        try {
            if (!transcode(vertices, compact, state.vertexCount())) {
                BlazeStats.sectionMeshLeftFull();
                return false;
            }
            compact.limit(state.vertexCount() * STRIDE);
            mesh.upload(new BlazeMeshState(LAYOUT, state.vertexCount(), state.indexCount(), state.topology(), state.indexBytes(), state.pattern()), compact, indices);
            BlazeStats.sectionMeshCompacted(state.vertexCount() * (long) (FULL_STRIDE - STRIDE));
            return true;
        } finally {
            MemoryUtil.memFree(compact);
        }
    }

    private static boolean fullLayout(BlazeMeshState.Layout layout) {
        return layout.stride() == FULL_STRIDE && layout.fields().size() >= 5
                && has(layout, "Position", 0, VK_FORMAT_R32G32B32_SFLOAT) && has(layout, "Color", 12, VK_FORMAT_R8G8B8A8_UNORM)
                && has(layout, "UV0", 16, VK_FORMAT_R32G32_SFLOAT) && has(layout, "UV2", 24, VK_FORMAT_R16G16_SINT)
                && has(layout, "Normal", 28, VK_FORMAT_R8G8B8_SNORM);
    }

    private static boolean has(BlazeMeshState.Layout layout, String name, int offset, int format) {
        for (var field : layout.fields()) if (field.name().equals(name)) return field.offset() == offset && field.nativeFormat() == format;
        return false;
    }

    private static boolean transcode(ByteBuffer vertices, ByteBuffer compact, int count) {
        long from = MemoryUtil.memAddress(vertices), to = MemoryUtil.memAddress(compact);
        for (int vertex = 0; vertex < count; vertex++, from += FULL_STRIDE, to += STRIDE) {
            int x = (int) ((MemoryUtil.memGetFloat(from) + POSITION_BIAS) * POSITION_SCALE + .5f);
            int y = (int) ((MemoryUtil.memGetFloat(from + 4) + POSITION_BIAS) * POSITION_SCALE + .5f);
            int z = (int) ((MemoryUtil.memGetFloat(from + 8) + POSITION_BIAS) * POSITION_SCALE + .5f);
            int u = (int) (MemoryUtil.memGetFloat(from + 16) * UV_SCALE + .5f), v = (int) (MemoryUtil.memGetFloat(from + 20) * UV_SCALE + .5f);
            int block = MemoryUtil.memGetShort(from + 24), sky = MemoryUtil.memGetShort(from + 26);
            if (((x | y | z | u | v) >>> 16 | (block | sky) >>> 8) != 0) return false;
            MemoryUtil.memPutShort(to, (short) x);
            MemoryUtil.memPutShort(to + 2, (short) y);
            MemoryUtil.memPutShort(to + 4, (short) z);
            MemoryUtil.memPutShort(to + 6, (short) (block | sky << 8));
            MemoryUtil.memPutInt(to + 8, MemoryUtil.memGetInt(from + 12));
            MemoryUtil.memPutShort(to + 12, (short) u);
            MemoryUtil.memPutShort(to + 14, (short) v);
            MemoryUtil.memPutInt(to + 16, MemoryUtil.memGetInt(from + 28));
        }
        return true;
    }

    /** This vertex shader reading the compact attributes, or null when it does not declare a position the usual way. */
    static String vertexSource(String source) {
        Matcher position = POSITION.matcher(source);
        if (!position.find()) return null;
        String result = position.replaceFirst("in uvec4 " + VERTEX_ATTRIBUTE + ";\n#define Position (vec3(" + VERTEX_ATTRIBUTE + ".xyz) / " + POSITION_SCALE + " - " + POSITION_BIAS + ")\n");
        result = UV0.matcher(result).replaceFirst("in uvec2 " + UV_ATTRIBUTE + ";\n#define UV0 (vec2(" + UV_ATTRIBUTE + ") / " + UV_SCALE + ")\n");
        return UV2.matcher(result).replaceFirst("\n#define UV2 ivec2(int(" + VERTEX_ATTRIBUTE + ".w & 255u), int(" + VERTEX_ATTRIBUTE + ".w >> 8u))\n");
    }

    /** Whether the compact program reads only what a compact mesh has, and takes the uniforms the original takes. */
    static boolean replaces(BlazeProgramCompiler.Program compact, BlazeProgramCompiler.Program original) {
        var bindings = compact.bindings();
        return READABLE.containsAll(bindings.attributes().keySet()) && bindings.uniformBytes() == original.bindings().uniformBytes()
                && bindings.uniforms().equals(original.bindings().uniforms()) && bindings.samplers().equals(original.bindings().samplers());
    }

    static boolean compact(BlazeMeshState state) {
        return state.layout() == LAYOUT;
    }

    static boolean compact(BlazeMeshState.Layout layout) {
        return layout == LAYOUT;
    }

    static boolean reads(BlazeShaderSnapshot shader) {
        return shader.program().bindings().attributes().containsKey(VERTEX_ATTRIBUTE);
    }
}
