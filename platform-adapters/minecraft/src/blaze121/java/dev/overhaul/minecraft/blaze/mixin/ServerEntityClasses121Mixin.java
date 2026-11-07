package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEntityClasses;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every server entity enters its level's lookup, the one entity scans read, through here. */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
abstract class ServerEntityClasses121Mixin {
    @Shadow @Final ServerLevel this$0;

    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"), require = 1)
    private void overhaul$entityTracked(Entity entity, CallbackInfo callback) {
        BlazeEntityClasses.added(this$0, entity);
    }
}
