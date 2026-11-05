package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.overhaul.minecraft.blaze.BlazeSectionBuffers;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** The vanilla map costs several cache misses per lookup, which over a thousand sections and five layers shows in the frame. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionBuffers121Mixin implements BlazeSectionBuffers {
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;
    @Unique private VertexBuffer[] overhaul$byLayer;

    @Override public VertexBuffer overhaul$buffer(int chunkLayerId) {
        VertexBuffer[] byLayer = overhaul$byLayer;
        if (byLayer == null) {
            var layers = RenderType.chunkBufferLayers();
            byLayer = new VertexBuffer[layers.size()];
            for (RenderType layer : layers) byLayer[layer.getChunkLayerId()] = buffers.get(layer);
            overhaul$byLayer = byLayer;
        }
        return byLayer[chunkLayerId];
    }
}
