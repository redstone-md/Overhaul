package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeSectionChanges;
import java.util.Queue;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A rebuild with geometry publishes its compile once its last upload runs, which happens while pending uploads drain;
 * so a drain that ran anything moves the change counter after it, when those compiles are out.
 */
@Mixin(ChunkRenderDispatcher.class)
abstract class ChunkPublish120Mixin {
    @Shadow @Final private Queue<Runnable> toUpload;
    @Unique private boolean overhaul$uploading;

    @Inject(method = "uploadAllPendingUploads", at = @At("HEAD"))
    private void overhaul$drainBegins(CallbackInfo callback) {
        overhaul$uploading = !toUpload.isEmpty();
    }

    @Inject(method = "uploadAllPendingUploads", at = @At("RETURN"))
    private void overhaul$drained(CallbackInfo callback) {
        if (overhaul$uploading) BlazeSectionChanges.changed();
    }
}
