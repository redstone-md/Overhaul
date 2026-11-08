package dev.overhaul.minecraft.blaze.mixin;

import com.seibel.distanthorizons.core.render.RenderParams;
import dev.overhaul.minecraft.blaze.BlazeDhFrame;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DH's raw TAA draws reach nothing on Vulkan, so its anti-aliasing runs natively on the same targets instead. */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer",
        "com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer_neoforge"}, remap = false)
abstract class DhTaaBlazeMixin {
    @Inject(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$native(RenderParams params, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        BlazeDhFrame.antiAlias(renderer, params);
        callback.cancel();
    }
}
