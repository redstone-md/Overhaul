package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazePackageClasses;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Born in Chaos syncs its animation names from one tick listener that tests every ticking entity against 83 of its
 * own classes, one after another. Every class it tests lives in its entity package, so an entity none of whose
 * superclasses is there leaves the listener before the tests.
 */
@Pseudo
@Mixin(targets = "net.mcreator.borninchaosv.init.EntityAnimationFactory", remap = false)
abstract class BornInChaosAnimationTick121Mixin {
    @Unique private static final BlazePackageClasses OVERHAUL$OWN = new BlazePackageClasses("net.mcreator.borninchaosv.entity.");

    @Inject(method = "onEntityTick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$skipOthers(EntityTickEvent.Pre event, CallbackInfo info) {
        if (event != null && event.getEntity() != null && !OVERHAUL$OWN.contains(event.getEntity())) info.cancel();
    }
}
