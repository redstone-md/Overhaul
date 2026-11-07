package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeBlockCollisions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every entity move gathers the block shapes around it through vanilla's {@code BlockCollisions} iterator before they
 * are copied into the move's collider list. They are gathered by {@link BlazeBlockCollisions#shapes} instead, with the
 * mover's own collision context. A level class that answers block collisions its own way keeps it.
 */
@Mixin(Entity.class)
abstract class EntityBlockCollisions121Mixin {
    @WrapOperation(method = "collectColliders", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/lang/Iterable;"),
            require = 0)
    private static Iterable<VoxelShape> overhaul$gatherBlocks(Level level, @Nullable Entity entity, AABB box, Operation<Iterable<VoxelShape>> blocks) {
        if (!BlazeBlockCollisions.vanillaShapes(level)) return blocks.call(level, entity, box);
        return BlazeBlockCollisions.shapes(level, entity == null ? CollisionContext.empty() : CollisionContext.of(entity), box);
    }
}
