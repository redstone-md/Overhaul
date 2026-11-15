package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.shader.BlazeShadowReplay;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells {@link BlazeShadowReplay} when a renderer asks the game's buffers for a buffer behind a recording's back. */
@Mixin(MultiBufferSource.BufferSource.class)
abstract class BufferSourceShadowReplayBlazeMixin {
    @Inject(method = "getBuffer", at = @At("HEAD"), require = 0)
    private void overhaul$watchReplay(RenderType type, CallbackInfoReturnable<?> callback) {
        BlazeShadowReplay.bufferRequested();
    }
}
