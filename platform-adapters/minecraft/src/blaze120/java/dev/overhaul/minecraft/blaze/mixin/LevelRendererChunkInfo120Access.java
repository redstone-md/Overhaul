package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The visible-chunk entry is package-private, so the batch reaches its chunk through this. */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer$RenderChunkInfo")
public interface LevelRendererChunkInfo120Access {
    @Accessor("chunk") RenderChunk overhaul$chunk();
}
