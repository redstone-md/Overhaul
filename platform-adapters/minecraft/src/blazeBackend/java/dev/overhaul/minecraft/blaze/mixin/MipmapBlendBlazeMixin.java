package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeMipColors;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mip levels averaged in linear light and weighted by alpha; Sodium and Embeddium do the same, so the plugin skips this beside them. */
@Mixin(MipmapGenerator.class)
abstract class MipmapBlendBlazeMixin {
    @Inject(method = "alphaBlend", at = @At("HEAD"), cancellable = true)
    private static void overhaul$blend(int one, int two, int three, int four, boolean checkAlpha, CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(BlazeMipColors.blend(one, two, three, four));
    }
}
