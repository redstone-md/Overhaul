package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeSectionChanges;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A rebuild without geometry has nothing to upload and publishes its compile before its task returns. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask")
abstract class RebuildPublish120Mixin {
    @Inject(method = "doTask", at = @At("RETURN"))
    private void overhaul$published(CallbackInfoReturnable<?> callback) {
        BlazeSectionChanges.changed();
    }
}
