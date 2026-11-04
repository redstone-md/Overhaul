package dev.overhaul.minecraft.blaze.mixin;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.overhaul.minecraft.blaze.BlazeDhFrame;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The fog pass's parameters go to the native composite, which applies them in one shader. */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.fog.GlDhFogRenderer",
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.fog.GlDhFogRenderer_neoforge"}, remap = false)
abstract class DhFogBlazeMixin {
    @Inject(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiFogRenderParam;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$capture(RenderParams params, DhApiFogRenderParam fog, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        BlazeDhFrame.captureFog(renderer, params, fog);
        callback.cancel();
    }
}
