package dev.overhaul.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

final class BlockClassifier {
    private BlockClassifier() { }
    static boolean opaque(BlockState state,Minecraft minecraft,BlockPos position) {
        return state.isSolidRender(minecraft.level,position);
    }
}
