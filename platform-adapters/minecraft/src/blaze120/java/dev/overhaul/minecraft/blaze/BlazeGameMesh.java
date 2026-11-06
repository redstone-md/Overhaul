package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Converts the pinned pre-MeshData format, retaining padding in its actual byte offsets. */
public final class BlazeGameMesh {
    private static final Map<VertexFormat, BlazeMeshState.Layout> LAYOUTS = new ConcurrentHashMap<>();

    private BlazeGameMesh() {}

    /** Asked on every upload; formats are long-lived constants. */
    public static BlazeMeshState.Layout layout(VertexFormat format) {
        return LAYOUTS.computeIfAbsent(format, BlazeGameMesh::convert);
    }

    private static BlazeMeshState.Layout convert(VertexFormat format) {
        var fields = new ArrayList<BlazeMeshState.Field>();
        var elements = format.getElements();
        var names = format.getElementAttributeNames();
        int offset = 0;
        for (int index = 0; index < elements.size(); index++) {
            var element = elements.get(index);
            if (element.getUsage() != VertexFormatElement.Usage.PADDING) {
                boolean normalized = element.getUsage() == VertexFormatElement.Usage.COLOR
                        || element.getUsage() == VertexFormatElement.Usage.NORMAL;
                boolean integer = element.getUsage() == VertexFormatElement.Usage.UV
                        && element.getType() != VertexFormatElement.Type.FLOAT;
                int nativeFormat = BlazeVertexFormats.format(BlazeVertexFormats.Type.valueOf(element.getType().name()),
                        element.getCount(), normalized, integer);
                fields.add(new BlazeMeshState.Field(names.get(index), offset, nativeFormat));
            }
            offset = Math.addExact(offset, element.getByteSize());
        }
        if (offset != format.getVertexSize()) throw new IllegalArgumentException("Vertex format padding differs from its stride");
        return new BlazeMeshState.Layout(offset, fields).canonical();
    }
}
