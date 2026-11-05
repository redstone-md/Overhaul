package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeDh;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts DH's first fade after the cutout layer: the end of the solid layer comes before any hook at the head of cutout. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererDhFade121Mixin {
    @Inject(method = "renderSectionLayer", at = @At("TAIL"))
    private void overhaul$placeFade(RenderType layer, double x, double y, double z, Matrix4f modelView, Matrix4f projection, CallbackInfo callback) {
        BlazeRenderer renderer = BlazeFrontend.current();
        BlazeDh dh = renderer == null ? null : renderer.dhIfUsed();
        if (dh == null) return;
        if (layer == RenderType.solid()) dh.holdFade();
        else if (layer == RenderType.cutout()) dh.releaseFade();
    }
}
