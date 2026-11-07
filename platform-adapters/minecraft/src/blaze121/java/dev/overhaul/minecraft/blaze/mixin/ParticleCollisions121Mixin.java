package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeBlockCollisions;
import java.util.List;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Particle moves collide through {@link BlazeBlockCollisions}, vanilla's answer without its gathering overhead. */
@Mixin(Particle.class)
abstract class ParticleCollisions121Mixin {
    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private Vec3 overhaul$openAir(Entity entity, Vec3 motion, AABB box, Level level, List<VoxelShape> shapes, Operation<Vec3> collide) {
        if (entity == null && shapes.isEmpty()) return BlazeBlockCollisions.collide(level, motion, box);
        return collide.call(entity, motion, box, level, shapes);
    }
}
