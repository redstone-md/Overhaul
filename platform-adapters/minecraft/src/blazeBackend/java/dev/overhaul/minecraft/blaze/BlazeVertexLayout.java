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
        for (var attribute : bindings.attributes().entrySet()) result.add(attribute(attribute.getKey(), attribute.getValue()));
        return List.copyOf(result);
    }

    NativeGraphicsPipeline.Attribute attribute(String name, int location) {
        for (var field : layout.fields()) {
            if (field.name().equals(name)) return new NativeGraphicsPipeline.Attribute(location, 0, field.nativeFormat(), field.offset());
        }
        throw new UnsupportedOperationException("Missing vertex attribute: " + name);
    }

}
