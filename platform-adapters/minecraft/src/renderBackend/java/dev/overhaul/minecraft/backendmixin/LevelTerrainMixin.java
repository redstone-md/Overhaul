package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.terrain.TerrainSectionAccess;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelRenderer.class)
public abstract class LevelTerrainMixin implements TerrainSectionAccess {
    @Shadow private ViewArea viewArea;
    @Override public SectionRenderDispatcher.RenderSection overhaul$section(BlockPos position) {
        return viewArea==null?null:viewArea.getRenderSectionAt(position);
    }
}
