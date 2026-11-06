package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.VertexBuffer;

/** A section's layer buffers by the layer's chunk id, which a batch asks for once per section per layer. */
public interface BlazeSectionBuffers {
    VertexBuffer overhaul$buffer(int chunkLayerId);
    BlazeMesh overhaul$mesh(int chunkLayerId);
    /** {@link BlazeCompiledContents} of the section's published compile, read without going through the reference and the compile. */
    long overhaul$contents();
    int overhaul$originX();
    int overhaul$originY();
    int overhaul$originZ();
}
