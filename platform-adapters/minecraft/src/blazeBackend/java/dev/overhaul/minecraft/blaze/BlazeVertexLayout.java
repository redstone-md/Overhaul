package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.ArrayList;
import java.util.List;

/** Resolves reflected shader names against the immutable captured native layout. */
final class BlazeVertexLayout {
    private final BlazeMeshState.Layout layout;
    BlazeVertexLayout(BlazeMeshState.Layout layout) { this.layout = layout; }

    List<NativeGraphicsPipeline.Attribute> attributes(ProgramBindings bindings) {
        List<NativeGraphicsPipeline.Attribute> result = new ArrayList<>();
        for (var attribute : bindings.attributes().entrySet()) {
            BlazeMeshState.Field field=null;
            for(var candidate:layout.fields())if(candidate.name().equals(attribute.getKey())){field=candidate;break;}
            if(field==null)throw new UnsupportedOperationException("Missing vertex attribute: "+attribute.getKey());
            result.add(new NativeGraphicsPipeline.Attribute(attribute.getValue(),0,field.nativeFormat(),field.offset()));
        }
        return List.copyOf(result);
    }

}
