package dev.overhaul.minecraft.terrain;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;

/** Read-only owner-thread observation of the actual section installed in the rotating view area. */
public interface TerrainSectionAccess {
    SectionRenderDispatcher.RenderSection overhaul$section(BlockPos position);
}
