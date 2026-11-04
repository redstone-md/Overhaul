package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeTerrain;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium keeps its section lists, batches and render state; only the region binding and the multi-draw go to Vulkan.
 * The render method itself stays intact so that other mods' injections into it still run.
 */
@Pseudo
@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class DefaultChunkRendererBlazeMixin {
    @Shadow @Final private SharedQuadIndexBuffer sharedIndexBuffer;
    @Unique private int overhaul$vertexStride;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void overhaul$captureStride(RenderDevice device, ChunkVertexType vertexType, CallbackInfo callback) {
        overhaul$vertexStride = vertexType.getVertexFormat().getStride();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void overhaul$beginPass(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
            TerrainRenderPass pass, CameraTransform camera, boolean indexedRendering, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer != null) renderer.terrain().begin(matrices.projection(), matrices.modelView(), overhaul$vertexStride, pass.supportsFragmentDiscard(),
                pass.isTranslucent() && indexedRendering, () -> sharedIndexBuffer.getBufferObject().handle());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void overhaul$endPass(ChunkRenderMatrices matrices, CommandList commands, ChunkRenderListIterable lists,
            TerrainRenderPass pass, CameraTransform camera, boolean indexedRendering, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer != null) renderer.terrain().end();
    }

    @Inject(method = "setModelMatrixUniforms", at = @At("HEAD"), cancellable = true)
    private static void overhaul$bindRegion(ChunkShaderInterface shader, RenderRegion region, CameraTransform camera, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        var resources = region.getResources();
        renderer.terrain().region(resources.getGeometryBuffer().handle(), resources.getIndexBuffer().handle(),
                region.getOriginX() - camera.intX - camera.fracX,
                region.getOriginY() - camera.intY - camera.fracY,
                region.getOriginZ() - camera.intZ - camera.fracZ);
        callback.cancel();
    }

    @Inject(method = "executeDrawBatch", at = @At("HEAD"), cancellable = true)
    private static void overhaul$drawBatch(CommandList commands, GlTessellation tessellation, MultiDrawBatch batch, CallbackInfo callback) {
        var renderer = BlazeFrontend.current();
        if (renderer == null) return;
        renderer.terrain().draw(batch.pElementPointer, batch.pElementCount, batch.pBaseVertex, batch.size);
        callback.cancel();
    }
}
