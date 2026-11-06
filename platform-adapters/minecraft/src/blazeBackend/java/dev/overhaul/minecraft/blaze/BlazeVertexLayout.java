package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves reflected shader names against the immutable captured native layout. */
final class BlazeVertexLayout {
    private final BlazeMeshState.Layout layout;
    BlazeVertexLayout(BlazeMeshState.Layout layout) { this.layout = layout; }

    /**
     * Matches by name first. Before 1.20.5 GL paired attributes with format elements by position, so a shader may say
     * {@code UV} where the format says {@code UV0}: the leftovers on both sides pair up in declaration order.
     */
    List<NativeGraphicsPipeline.Attribute> attributes(ProgramBindings bindings) {
        List<NativeGraphicsPipeline.Attribute> result = new ArrayList<>();
        List<Map.Entry<String, Integer>> unmatched = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        for (var attribute : bindings.attributes().entrySet()) {
            BlazeMeshState.Field field = field(attribute.getKey());
            if (field == null) unmatched.add(attribute);
            else {
                claimed.add(field.name());
                result.add(attribute(field, attribute.getValue()));
            }
        }
        if (!unmatched.isEmpty()) {
            unmatched.sort(Comparator.comparingInt(Map.Entry::getValue));
            var spare = layout.fields().stream().filter(field -> !claimed.contains(field.name())).iterator();
            for (var attribute : unmatched) {
                if (!spare.hasNext()) throw new UnsupportedOperationException("Missing vertex attribute: " + attribute.getKey());
                result.add(attribute(spare.next(), attribute.getValue()));
            }
        }
        return List.copyOf(result);
    }

    NativeGraphicsPipeline.Attribute attribute(String name, int location) {
        BlazeMeshState.Field field = field(name);
        if (field == null) throw new UnsupportedOperationException("Missing vertex attribute: " + name);
        return attribute(field, location);
    }

    private BlazeMeshState.Field field(String name) {
        for (var field : layout.fields()) if (field.name().equals(name)) return field;
        return null;
    }

    private static NativeGraphicsPipeline.Attribute attribute(BlazeMeshState.Field field, int location) {
        return new NativeGraphicsPipeline.Attribute(location, 0, field.nativeFormat(), field.offset());
    }
}
