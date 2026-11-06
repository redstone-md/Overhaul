package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeVeilPrograms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Veil's full-screen triangle is a raw vertex-array draw. Its tessellation check asks OpenGL, which this backend
 * does not run; tessellated programs run as geometry stages, so the device's geometry support answers instead.
 */
@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.VeilRenderSystem", remap = false)
abstract class VeilScreenQuad121Mixin {
    @Inject(method = "drawScreenQuad", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$draw(CallbackInfo callback) {
        if (BlazeFrontend.current() == null) return;
        BlazeVeilPrograms.drawScreenTriangle();
        callback.cancel();
    }

    @Inject(method = "tessellationSupported", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$tessellation(CallbackInfoReturnable<Boolean> callback) {
        // Veil may ask before the device opens; OpenGL then answers as before.
        if (!BlazeFrontend.requested()) return;
        try {
            var renderer = BlazeFrontend.current();
            if (renderer != null) callback.setReturnValue(renderer.geometryShaders());
        } catch (IllegalStateException opening) {
            // No device yet.
        }
    }
}
