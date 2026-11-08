package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.stream.Stream;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every move streams the blocks inside the mover to see whether any is fire or lava. When none is, a mover that is
 * not burning has its fire ticks set to the value they already hold, and only one that was burning may hear the fire
 * go out. When none is not, nothing happens. So for a mover that is not burning, holding exactly that value and not
 * burning the move before, both answers do the same thing, and the blocks are not streamed.
 */
@Mixin(Entity.class)
abstract class EntityFireScan121Mixin {
    @Shadow private int remainingFireTicks;
    @Shadow public boolean wasOnFire;

    @Shadow protected abstract int getFireImmuneTicks();

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockStatesIfLoaded(Lnet/minecraft/world/phys/AABB;)Ljava/util/stream/Stream;"),
            require = 0)
    private Stream<BlockState> overhaul$skipCalmScan(Level level, AABB box, Operation<Stream<BlockState>> states) {
        if (!wasOnFire && remainingFireTicks == -getFireImmuneTicks()) return Stream.empty();
        return states.call(level, box);
    }
}
