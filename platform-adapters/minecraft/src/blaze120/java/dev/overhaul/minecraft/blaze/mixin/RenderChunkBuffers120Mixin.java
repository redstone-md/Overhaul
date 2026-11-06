package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.overhaul.minecraft.blaze.BlazeChunkLayers;
import dev.overhaul.minecraft.blaze.BlazeCompiledContents;
import dev.overhaul.minecraft.blaze.BlazeMesh;
import dev.overhaul.minecraft.blaze.BlazeSectionBuffers;
import dev.overhaul.minecraft.blaze.BlazeSectionChanges;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Layer buffers by chunk layer id and the published compile as one number. This version publishes the compile from a
 * lambda, so the contents are read through the reference, and the change counter moves where the publish completes.
 */
@Mixin(ChunkRenderDispatcher.RenderChunk.class)
abstract class RenderChunkBuffers120Mixin implements BlazeSectionBuffers {
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;
    @Shadow @Final public AtomicReference<ChunkRenderDispatcher.CompiledChunk> compiled;
    @Unique private VertexBuffer[] overhaul$byLayer;
    @Unique private BlazeMesh[] overhaul$meshes;
    @Unique private int overhaul$originX, overhaul$originY, overhaul$originZ;

    @Inject(method = "reset", at = @At("TAIL"))
    private void overhaul$reset(CallbackInfo callback) {
        BlazeSectionChanges.changed();
    }

    @Inject(method = "setDirty", at = @At("TAIL"))
    private void overhaul$markedDirty(boolean playerChanged, CallbackInfo callback) {
        BlazeSectionChanges.changed();
    }

    @Inject(method = "setOrigin", at = @At("HEAD"))
    private void overhaul$originMoves(int x, int y, int z, CallbackInfo callback) {
        BlazeSectionChanges.originMoved();
    }

    @Inject(method = "setOrigin", at = @At("TAIL"))
    private void overhaul$keepOrigin(int x, int y, int z, CallbackInfo callback) {
        overhaul$originX = x;
        overhaul$originY = y;
        overhaul$originZ = z;
        BlazeSectionChanges.changed();
    }

    @Override public long overhaul$contents() {
        return ((BlazeCompiledContents) compiled.get()).overhaul$contents();
    }

    @Override public int overhaul$originX() { return overhaul$originX; }
    @Override public int overhaul$originY() { return overhaul$originY; }
    @Override public int overhaul$originZ() { return overhaul$originZ; }

    @Override public VertexBuffer overhaul$buffer(int chunkLayerId) {
        VertexBuffer[] byLayer = overhaul$byLayer;
        if (byLayer == null) {
            var layers = RenderType.chunkBufferLayers();
            byLayer = new VertexBuffer[layers.size()];
            for (RenderType layer : layers) byLayer[BlazeChunkLayers.id(layer)] = buffers.get(layer);
            overhaul$byLayer = byLayer;
        }
        return byLayer[chunkLayerId];
    }

    /** A buffer keeps its mesh until it closes, and a closed mesh draws nothing, so only the missing are asked for again. */
    @Override public BlazeMesh overhaul$mesh(int chunkLayerId) {
        BlazeMesh[] meshes = overhaul$meshes;
        if (meshes == null) overhaul$meshes = meshes = new BlazeMesh[RenderType.chunkBufferLayers().size()];
        BlazeMesh mesh = meshes[chunkLayerId];
        if (mesh == null) meshes[chunkLayerId] = mesh = ((BlazeVertexBufferAccess) overhaul$buffer(chunkLayerId)).overhaul$meshOrNull();
        return mesh;
    }
}
