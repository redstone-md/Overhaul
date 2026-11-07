package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEntityClasses;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every move asks the level for entities it could stand on, and only boats, minecarts, shulkers and the like answer.
 * While the level holds none of them, the answer is empty without walking the sections around the mover.
 */
@Mixin(EntityGetter.class)
interface EntityCollisions121Mixin {
    @Inject(method = "getEntityCollisions", at = @At("HEAD"), cancellable = true, require = 1)
    private void overhaul$skipWithoutColliders(@Nullable Entity mover, AABB box, CallbackInfoReturnable<List<VoxelShape>> callback) {
        if ((Object) this instanceof Level level && BlazeEntityClasses.noColliders(level, ((LevelEntities121Access) level).overhaul$entities(), mover)) {
            callback.setReturnValue(List.of());
        }
    }
}
