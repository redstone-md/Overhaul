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
 * A {@link RemoveBlockGoal} that finds its target as vanilla does skips the block walk when no loaded section around
 * the mob holds the block it is after, see {@link BlazeBlockSearch#mayFind}. The walk would have found nothing.
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
        if (goal instanceof RemoveBlockGoal remove && verticalSearchStart <= verticalSearchRange && BlazeBlockSearch.vanilla(goal)
                && !BlazeBlockSearch.mayFind(mob.level(), mob.blockPosition(), searchRange,
                        1 - Math.min(verticalSearchStart, -verticalSearchRange), verticalSearchRange - 1,
                        ((RemoveBlockGoalAccess121Mixin) remove).overhaul$blockToRemove())) return false;
        return original.call();
    }
}
