package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.NativeGraphicsPipeline;
import java.util.List;

/** Pipeline facts of a program that is not a game shader: its colour outputs and where its vertex inputs read from. */
interface BlazeProgramShape {
    /** One state per colour location; {@code inherited} is what the game asked for at this draw. */
    List<NativeGraphicsPipeline.Color> colors(NativeGraphicsPipeline.Color inherited);
    List<NativeGraphicsPipeline.Binding> bindings(BlazeMeshState.Layout layout);
    List<NativeGraphicsPipeline.Attribute> attributes(BlazeMeshState.Layout layout);
    /** Vertex buffer bound at slot 1 for inputs the mesh does not have; 0 when no such slot exists. */
    long vertexConstants();
}
