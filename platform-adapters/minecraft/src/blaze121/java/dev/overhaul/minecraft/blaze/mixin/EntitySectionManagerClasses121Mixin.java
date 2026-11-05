package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEntityClasses;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every entity on the client joins through here. */
@Mixin(TransientEntitySectionManager.class)
abstract class EntitySectionManagerClasses121Mixin {
    @Inject(method = "addEntity", at = @At("RETURN"), require = 1)
    private void overhaul$entityAdded(CallbackInfo callback) {
        BlazeEntityClasses.added();
    }
}
