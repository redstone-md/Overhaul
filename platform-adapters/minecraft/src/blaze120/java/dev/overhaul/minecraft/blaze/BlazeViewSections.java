package dev.overhaul.minecraft.blaze;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk;
import net.minecraft.core.BlockPos;

/** The game's section grid, named as this version names it, for code shared across versions. */
public final class BlazeViewSections {
    private BlazeViewSections() {}

    /** Every section of the grid, each a {@link BlazeSectionBuffers}. */
    public static Object[] all(ViewArea area) {
        return area.chunks;
    }

    public static BlockPos origin(Object section) {
        return ((RenderChunk) section).getOrigin();
    }
}
