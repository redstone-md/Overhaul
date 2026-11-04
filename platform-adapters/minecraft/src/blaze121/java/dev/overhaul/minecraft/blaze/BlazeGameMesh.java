package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.ArrayList;

/** Converts the pinned record-based format using its authoritative element offsets. */
public final class BlazeGameMesh {
    private BlazeGameMesh() {}

    public static BlazeMeshState.Layout layout(VertexFormat format) {
        var fields = new ArrayList<BlazeMeshState.Field>();
        var elements = format.getElements();
        var names = format.getElementAttributeNames();
        for (int index = 0; index < elements.size(); index++) {
            var element = elements.get(index);
            boolean normalized = element.usage() == VertexFormatElement.Usage.COLOR
                    || element.usage() == VertexFormatElement.Usage.NORMAL;
            boolean integer = element.usage() == VertexFormatElement.Usage.UV
                    && element.type() != VertexFormatElement.Type.FLOAT;
            int nativeFormat = BlazeVertexFormats.format(BlazeVertexFormats.Type.valueOf(element.type().name()),
                    element.count(), normalized, integer);
            fields.add(new BlazeMeshState.Field(names.get(index), format.getOffset(element), nativeFormat));
        }
        return new BlazeMeshState.Layout(format.getVertexSize(), fields);
    }
}
