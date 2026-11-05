package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeCompiledContents;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Both collections are filled before the rebuild task publishes the compile, and two threads asking compute the same number. */
@Mixin(SectionRenderDispatcher.CompiledSection.class)
abstract class CompiledContents121Mixin implements BlazeCompiledContents {
    @Shadow @Final Set<RenderType> hasBlocks;
    @Shadow @Final List<BlockEntity> renderableBlockEntities;
    @Unique private long overhaul$contents;

    @Override public long overhaul$contents() {
        long contents = overhaul$contents;
        if (contents != 0) return contents;
        contents = KNOWN;
        if (!renderableBlockEntities.isEmpty()) contents |= BLOCK_ENTITIES;
        for (RenderType type : hasBlocks) contents |= 1L << type.getChunkLayerId();
        return overhaul$contents = contents;
    }
}
