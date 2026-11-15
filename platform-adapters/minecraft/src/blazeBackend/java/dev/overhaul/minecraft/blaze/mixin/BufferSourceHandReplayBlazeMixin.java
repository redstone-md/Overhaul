package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.shader.BlazeHandReplay;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells {@link BlazeHandReplay} when a renderer asks the game's buffers for a buffer behind a hand recording's back. */
@Mixin(MultiBufferSource.BufferSource.class)
abstract class BufferSourceHandReplayBlazeMixin {
    @Inject(method = "getBuffer", at = @At("HEAD"), require = 0)
    private void overhaul$watchHand(RenderType type, CallbackInfoReturnable<?> callback) {
        BlazeHandReplay.bufferRequested();
    }
}
