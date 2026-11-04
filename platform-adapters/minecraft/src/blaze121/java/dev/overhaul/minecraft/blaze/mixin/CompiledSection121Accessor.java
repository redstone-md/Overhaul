package dev.overhaul.minecraft.blaze.mixin;

import java.util.Set;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionRenderDispatcher.CompiledSection.class)
public interface CompiledSection121Accessor {
    @Accessor("hasBlocks")
    Set<RenderType> overhaul$hasBlocks();
}
