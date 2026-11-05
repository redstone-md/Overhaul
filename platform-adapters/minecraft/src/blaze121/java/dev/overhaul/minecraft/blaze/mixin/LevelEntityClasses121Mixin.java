package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeEntityClasses;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mods scan for their own entities on every frame of every model, mostly for classes that are not in the world. Part entities are still looked at. */
@Mixin(Level.class)
abstract class LevelEntityClasses121Mixin {
    @WrapOperation(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/LevelEntityGetter;get(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V"),
            require = 1)
    private void overhaul$skipAbsentClass(LevelEntityGetter<Entity> entities, EntityTypeTest<Entity, ?> test, AABB box, AbortableIterationConsumer<?> consumer, Operation<Void> get) {
        if (!BlazeEntityClasses.absent((Level) (Object) this, entities, test.getBaseClass())) get.call(entities, test, box, consumer);
    }
}
