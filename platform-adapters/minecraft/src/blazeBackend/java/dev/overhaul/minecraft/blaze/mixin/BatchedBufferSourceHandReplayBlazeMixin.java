package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.shader.BlazeHandReplay;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ImmediatelyFast's batching buffers answer for the game's buffers without calling them; watched as they are. */
@Pseudo
@Mixin(targets = "net.raphimc.immediatelyfast.feature.core.BatchableBufferSource", remap = false)
abstract class BatchedBufferSourceHandReplayBlazeMixin {
    @Inject(method = "getBuffer", at = @At("HEAD"), require = 0)
    private void overhaul$watchHand(RenderType type, CallbackInfoReturnable<?> callback) {
        BlazeHandReplay.bufferRequested();
    }
}
