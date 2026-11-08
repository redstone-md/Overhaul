package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.blaze.BlazeBlockSearch;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A {@link RemoveBlockGoal} that finds its target as vanilla does, or an Alex's Mobs crow after crops, skips the block
 * walk when no section around the mob holds a block it is after, see {@link BlazeBlockSearch#mayFind}. The walk would
 * have found nothing.
 */
@Mixin(MoveToBlockGoal.class)
abstract class MoveToBlockSearch121Mixin {
    @Shadow @Final protected PathfinderMob mob;
    @Shadow @Final private int searchRange;
    @Shadow @Final private int verticalSearchRange;
    @Shadow protected int verticalSearchStart;

    @WrapMethod(method = "findNearestBlock")
    private boolean overhaul$skipAbsentBlock(Operation<Boolean> original) {
        MoveToBlockGoal goal = (MoveToBlockGoal) (Object) this;
        // The walk offsets y by k - 1 for k from min(start, -range) up to range; past that it runs no step at all.
        if (verticalSearchStart > verticalSearchRange) return original.call();
        int below = 1 - Math.min(verticalSearchStart, -verticalSearchRange), above = verticalSearchRange - 1;
        if (goal instanceof RemoveBlockGoal remove && BlazeBlockSearch.vanilla(goal)
                && !BlazeBlockSearch.mayFind(mob.level(), mob.blockPosition(), searchRange, below, above,
                        ((RemoveBlockGoalAccess121Mixin) remove).overhaul$blockToRemove())) return false;
        if (BlazeBlockSearch.crowCrops(goal)
                && !BlazeBlockSearch.mayFindCrowFood(mob.level(), mob.blockPosition(), searchRange, below, above)) return false;
        return original.call();
    }
}
