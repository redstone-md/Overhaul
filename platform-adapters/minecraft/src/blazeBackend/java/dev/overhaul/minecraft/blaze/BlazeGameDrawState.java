package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.VertexFormat;
import static org.lwjgl.vulkan.VK10.*;

/** The two pinned game versions share primitive semantics, but expose different upload containers. */
public final class BlazeGameDrawState {
    private BlazeGameDrawState() {}

    public static BlazeMeshState capture(VertexFormat format, int vertices, int indices,
            VertexFormat.Mode mode, VertexFormat.IndexType indexType) {
        int topology = switch (mode) {
            case QUADS, LINES, TRIANGLES -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case LINE_STRIP, TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            case DEBUG_LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case DEBUG_LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
        };
        var pattern = switch (mode) {
            case QUADS -> BlazeMeshState.IndexPattern.QUADS;
            case LINES -> BlazeMeshState.IndexPattern.EXPANDED_LINES;
            default -> BlazeMeshState.IndexPattern.SEQUENTIAL;
        };
        return new BlazeMeshState(((BlazeFormatAccess) format).overhaul$layout(), vertices, indices,
                topology, indexType == VertexFormat.IndexType.SHORT ? 2 : 4, pattern);
    }
}
