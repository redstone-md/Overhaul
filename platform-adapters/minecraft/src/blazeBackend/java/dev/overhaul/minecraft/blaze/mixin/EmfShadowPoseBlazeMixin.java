package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeShadowAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity Model Features asks this before each animation whether to skip it and reuse the entity's last result; while
 * the shadow pass paces the local player, the answer is yes. See {@link BlazeShadowAnimation}.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.EMFAnimationEntityContext", remap = false)
abstract class EmfShadowPoseBlazeMixin {
    @Inject(method = "isLODSkippingThisFrame", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$reusePose(String model, CallbackInfoReturnable<Boolean> callback) {
        if (BlazeShadowAnimation.reusing()) callback.setReturnValue(true);
    }
}
