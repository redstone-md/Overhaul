package dev.overhaul.minecraft.blaze.mixin;

import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import dev.overhaul.minecraft.blaze.BlazeDhFrame;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DH's debug box outlines are raw line draws, which reach nothing on Vulkan. Its GL setup around them only touches
 * answered names, so it stays; each box goes into a native line pass instead.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.GlDhDebugWireframeRenderer", remap = false)
abstract class DhWireframeBlazeMixin {
    @Inject(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;)V", at = @At("HEAD"), require = 0)
    private void overhaul$begin(RenderParams params, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer != null) BlazeDhFrame.beginWireframe(renderer, params);
    }

    @Inject(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;)V", at = @At("RETURN"), require = 0)
    private void overhaul$end(RenderParams params, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer != null) BlazeDhFrame.endWireframe(renderer);
    }

    @Inject(method = "renderBox(Lcom/seibel/distanthorizons/core/render/renderer/AbstractDebugWireframeRenderer$Box;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$box(AbstractDebugWireframeRenderer.Box box, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        BlazeDhFrame.wireframeBox(renderer, box.minPos.x, box.minPos.y, box.minPos.z, box.maxPos.x, box.maxPos.y, box.maxPos.z, box.color.getRGB());
        callback.cancel();
    }
}
