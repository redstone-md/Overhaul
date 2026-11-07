package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeBlockCollisions;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An entity on the ground looks for the block it stands on after every move, through the same {@code BlockCollisions}
 * iterator. {@link BlazeBlockCollisions#supportingBlock} walks the same blocks without it. A level class that finds
 * supporting blocks its own way keeps it.
 */
@Mixin(Entity.class)
abstract class EntitySupportingBlock121Mixin {
    @WrapOperation(method = "checkSupportingBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;findSupportingBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/Optional;"),
            require = 0)
    private Optional<BlockPos> overhaul$findSupport(Level level, Entity entity, AABB box, Operation<Optional<BlockPos>> find) {
        if (!BlazeBlockCollisions.vanillaSupport(level)) return find.call(level, entity, box);
        return BlazeBlockCollisions.supportingBlock(level, entity, box);
    }
}
