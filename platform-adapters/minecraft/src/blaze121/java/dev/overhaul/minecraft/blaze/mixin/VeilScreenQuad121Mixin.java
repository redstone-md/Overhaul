package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeVeilPrograms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Veil's full-screen triangle is a raw vertex-array draw. */
@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.VeilRenderSystem", remap = false)
abstract class VeilScreenQuad121Mixin {
    @Inject(method = "drawScreenQuad", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$draw(CallbackInfo callback) {
        if (BlazeFrontend.current() == null) return;
        BlazeVeilPrograms.drawScreenTriangle();
        callback.cancel();
    }
}
