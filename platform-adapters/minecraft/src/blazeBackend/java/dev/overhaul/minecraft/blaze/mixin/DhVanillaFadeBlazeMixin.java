package dev.overhaul.minecraft.blaze.mixin;

import com.seibel.distanthorizons.core.render.RenderParams;
import dev.overhaul.minecraft.blaze.BlazeDhFrame;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DH's own fade draws a raw screen quad, which reaches nothing on Vulkan; its config still decides when this runs. */
@Pseudo
@Mixin(targets = {"com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlVanillaFadeRenderer",
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlVanillaFadeRenderer_neoforge"}, remap = false)
abstract class DhVanillaFadeBlazeMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$fade(RenderParams params, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        BlazeDhFrame.vanillaFade(renderer, params);
        callback.cancel();
    }
}
