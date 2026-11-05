package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells a buffer that it holds terrain, the only geometry known to fit the compact layout's ranges. */
@Mixin(SectionRenderDispatcher.class)
abstract class SectionRenderDispatcherCompact121Mixin {
    @Inject(method = "uploadSectionLayer", at = @At("HEAD"))
    private void overhaul$markSectionBuffer(MeshData mesh, VertexBuffer buffer, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        ((BlazeVertexBufferAccess) buffer).overhaul$markSectionUploads();
    }
}
