package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A fluid block works out its biome-blended tint before it looks at its neighbours, and returns without drawing when
 * none of its six faces shows. Most water in a meshed section is enclosed by more water, so the tint is asked for
 * nothing. The faces are checked first with the renderer's own tests; a block that will draw nothing gets a colour
 * it never reads, and every block that draws gets the real tint.
 */
@Mixin(LiquidBlockRenderer.class)
abstract class LiquidBlockRendererTint121Mixin {
    @Shadow
    private static boolean isNeighborStateHidingOverlay(FluidState fluid, BlockState neighbour, Direction side) {
        throw new AssertionError();
    }

    @Shadow
    private static boolean isFaceOccludedByNeighbor(BlockGetter level, BlockPos position, Direction side, float height, BlockState neighbour) {
        throw new AssertionError();
    }

    @WrapOperation(method="tesselate",require=0,at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/client/extensions/common/IClientFluidTypeExtensions;getTintColor(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private int overhaul$tintWhenShown(IClientFluidTypeExtensions type, FluidState fluid, BlockAndTintGetter level, BlockPos position, Operation<Integer> tint, @Local(argsOnly=true) BlockState block){
        if (!overhaul$anyFaceShown(level, position, fluid, block)) return -1;
        return tint.call(type, fluid, level, position);
    }

    private static boolean overhaul$anyFaceShown(BlockAndTintGetter level, BlockPos position, FluidState fluid, BlockState block){
        if (!isNeighborStateHidingOverlay(fluid, level.getBlockState(position.above()), Direction.DOWN)) return true;
        BlockState below = level.getBlockState(position.below());
        if (LiquidBlockRenderer.shouldRenderFace(level, position, fluid, block, Direction.DOWN, below)
            && !isFaceOccludedByNeighbor(level, position, Direction.DOWN, 0.8888889F, below)) return true;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (LiquidBlockRenderer.shouldRenderFace(level, position, fluid, block, side, level.getBlockState(position.relative(side)))) return true;
        }
        return false;
    }
}
