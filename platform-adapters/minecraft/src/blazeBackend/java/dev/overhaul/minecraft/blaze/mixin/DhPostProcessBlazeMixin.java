package dev.overhaul.minecraft.blaze.mixin;

import com.seibel.distanthorizons.core.render.RenderParams;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Passes with no native version yet. Their raw draws would reach nothing on Vulkan, and their setup would still
 * retarget the framebuffers and texture units the next pass reads.
 */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer",
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer_neoforge",
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlDhFarFadeRenderer",
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlDhFarFadeRenderer_neoforge",
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlVanillaFadeRenderer",
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlVanillaFadeRenderer_neoforge"}, remap = false)
abstract class DhPostProcessBlazeMixin {
    @Inject(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$skip(RenderParams params, CallbackInfo callback) {
        if (BlazeFrontend.current() != null) callback.cancel();
    }
}
