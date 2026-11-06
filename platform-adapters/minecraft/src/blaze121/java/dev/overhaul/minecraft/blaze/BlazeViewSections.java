package dev.overhaul.minecraft.blaze;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.BlockPos;

/** The game's section grid, named as this version names it, for code shared across versions. */
public final class BlazeViewSections {
    private BlazeViewSections() {}

    /** Every section of the grid, each a {@link BlazeSectionBuffers}. */
    public static Object[] all(ViewArea area) {
        return area.sections;
    }

    public static BlockPos origin(Object section) {
        return ((RenderSection) section).getOrigin();
    }
}
