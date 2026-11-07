package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block a {@link RemoveBlockGoal} is after, for the search shortcut in {@link MoveToBlockSearch121Mixin}. */
@Mixin(RemoveBlockGoal.class)
public interface RemoveBlockGoalAccess121Mixin {
    @Accessor("blockToRemove")
    Block overhaul$blockToRemove();
}
