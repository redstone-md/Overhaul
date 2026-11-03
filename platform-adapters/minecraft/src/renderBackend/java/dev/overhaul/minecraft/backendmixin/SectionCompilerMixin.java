package dev.overhaul.minecraft.backendmixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.overhaul.minecraft.terrain.OverhaulSectionCompiler;
import dev.overhaul.minecraft.terrain.TerrainCompilerAccess;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces ordinary section compilation only in the opt-in owned-renderer build. */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin implements TerrainCompilerAccess {
    @Unique private OverhaulSectionCompiler overhaul$compiler;

    @Inject(method="<init>",at=@At("RETURN"))
    private void overhaul$initialize(boolean ambientOcclusion,boolean cutoutLeaves,BlockStateModelSet blocks,
            FluidStateModelSet fluids,BlockColors colors,CallbackInfo callback) {
        overhaul$compiler=new OverhaulSectionCompiler(ambientOcclusion,cutoutLeaves,blocks,fluids,colors);
    }
    @Override public OverhaulSectionCompiler overhaul$compiler() { return overhaul$compiler; }
    @Inject(method="compile",at=@At("HEAD"),cancellable=true)
    private void overhaul$compile(SectionPos section,RenderSectionRegion region,VertexSorting sorting,
            SectionBufferBuilderPack buffers,CallbackInfoReturnable<SectionCompiler.Results> callback) {
        callback.setReturnValue(overhaul$compiler.compile(section,region,sorting,buffers));
    }
}
