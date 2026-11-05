package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.compat.ProgramBindings;
import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;

/** A terrain program whose section offset arrives per instance: slot 0 is the mesh, slot 1 holds one offset per draw. */
record BlazeSectionShape(ProgramBindings program) implements BlazeProgramShape {
    static final String OFFSET_ATTRIBUTE = "OverhaulChunkOffset";
    static final int OFFSET_BYTES = 12;

    @Override public List<NativeGraphicsPipeline.Color> colors(NativeGraphicsPipeline.Color inherited) {
        return List.of(inherited);
    }

    @Override public List<NativeGraphicsPipeline.Binding> bindings(BlazeMeshState.Layout layout) {
        return List.of(new NativeGraphicsPipeline.Binding(0, layout.stride(), 0), new NativeGraphicsPipeline.Binding(1, OFFSET_BYTES, 1));
    }

    @Override public List<NativeGraphicsPipeline.Attribute> attributes(BlazeMeshState.Layout layout) {
        BlazeVertexLayout mesh = new BlazeVertexLayout(layout);
        List<NativeGraphicsPipeline.Attribute> result = new ArrayList<>();
        for (var attribute : program.attributes().entrySet()) {
            result.add(attribute.getKey().equals(OFFSET_ATTRIBUTE)
                    ? new NativeGraphicsPipeline.Attribute(attribute.getValue(), 1, VK_FORMAT_R32G32B32_SFLOAT, 0)
                    : mesh.attribute(attribute.getKey(), attribute.getValue()));
        }
        return List.copyOf(result);
    }

    @Override public long vertexConstants() {
        return 0;
    }
}
