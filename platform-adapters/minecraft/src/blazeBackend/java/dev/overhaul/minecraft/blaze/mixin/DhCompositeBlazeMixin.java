package dev.overhaul.minecraft.blaze.mixin;

import com.seibel.distanthorizons.core.render.RenderParams;
import dev.overhaul.minecraft.blaze.BlazeDhFrame;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DH's raw screen quad draws nothing on Vulkan, so LODs reach the frame through the native composite instead. */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer",
        "com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_neoforge"}, remap = false)
abstract class DhCompositeBlazeMixin {
    @Shadow(remap = false) public abstract int getActiveColorTextureId();
    @Shadow(remap = false) public abstract int getActiveDepthTextureId();

    @Inject(method = "copyToMcTexture", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$composite(RenderParams params, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        BlazeDhFrame.composite(renderer, params, getActiveColorTextureId(), getActiveDepthTextureId());
        callback.cancel();
    }
}
