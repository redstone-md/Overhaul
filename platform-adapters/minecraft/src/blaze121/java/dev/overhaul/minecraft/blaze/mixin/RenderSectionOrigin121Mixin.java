package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeSectionBounds;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSection.class)
abstract class RenderSectionOrigin121Mixin {
    @Inject(method = "setOrigin", at = @At("HEAD"))
    private void overhaul$originMoved(CallbackInfo callback) {
        BlazeSectionBounds.originMoved();
    }
}
