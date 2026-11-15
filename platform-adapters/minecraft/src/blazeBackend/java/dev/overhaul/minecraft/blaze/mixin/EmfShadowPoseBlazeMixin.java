package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeHandAnimation;
import dev.overhaul.minecraft.blaze.BlazeShadowAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity Model Features asks this before each animation whether to skip it and reuse the entity's last result; while
 * the shadow pass or the first-person hand paces the local player, the answer is yes. It also hands the time of the
 * frames a paced player skipped to its next animation. See {@link BlazeShadowAnimation} and {@link BlazeHandAnimation}.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.EMFAnimationEntityContext", remap = false)
abstract class EmfShadowPoseBlazeMixin {
    @Shadow
    private static float frameCounter;

    @Shadow
    public static float getFrameTime() {
        throw new AssertionError();
    }

    @Inject(method = "isLODSkippingThisFrame", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$reusePose(String model, CallbackInfoReturnable<Boolean> callback) {
        if (BlazeShadowAnimation.reusing()) {
            callback.setReturnValue(true);
        } else if (BlazeHandAnimation.keep(model, frameCounter)) {
            BlazeHandAnimation.owe(frameCounter, getFrameTime());
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "getFrameTime", at = @At("RETURN"), cancellable = true, require = 0)
    private static void overhaul$owedTime(CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue(BlazeHandAnimation.frameTime(callback.getReturnValueF(), frameCounter));
    }
}
