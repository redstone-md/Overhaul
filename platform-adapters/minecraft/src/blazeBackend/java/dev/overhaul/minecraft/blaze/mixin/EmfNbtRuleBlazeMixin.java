package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEntityNbt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entity Model Features' {@code nbt()} animation rule answered from {@link BlazeEntityNbt} for the rest of the tick. */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.math.methods.emf.NBTMethod", remap = false)
abstract class EmfNbtRuleBlazeMixin {
    @Unique
    private static Object overhaul$entity;

    @Inject(method = "nbtMethodStatic", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$knownRule(String path, String test, CallbackInfoReturnable<Boolean> callback) {
        Object entity = BlazeEntityNbt.ruleEntity();
        overhaul$entity = entity;
        if (entity == null) return;
        Boolean answer = BlazeEntityNbt.rule(entity, path, test);
        if (answer != null) {
            overhaul$entity = null;
            callback.setReturnValue(answer);
        }
    }

    @Inject(method = "nbtMethodStatic", at = @At("RETURN"), require = 0)
    private static void overhaul$rememberRule(String path, String test, CallbackInfoReturnable<Boolean> callback) {
        Object entity = overhaul$entity;
        overhaul$entity = null;
        if (entity != null) BlazeEntityNbt.remember(entity, path, test, callback.getReturnValueZ());
    }
}
