package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.VertexBuffer;

/** A section's layer buffers by the layer's chunk id, which a batch asks for once per section per layer. */
public interface BlazeSectionBuffers {
    VertexBuffer overhaul$buffer(int chunkLayerId);
}
