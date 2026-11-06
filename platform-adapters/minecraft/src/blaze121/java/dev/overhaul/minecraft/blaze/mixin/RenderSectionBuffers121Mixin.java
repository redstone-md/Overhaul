package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeChunkLayers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.overhaul.minecraft.blaze.BlazeCompiledContents;
import dev.overhaul.minecraft.blaze.BlazeMesh;
import dev.overhaul.minecraft.blaze.BlazeSectionBuffers;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import dev.overhaul.minecraft.blaze.BlazeVisibleSections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The vanilla map costs several cache misses per lookup, which over a thousand sections and five layers shows in the frame. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionBuffers121Mixin implements BlazeSectionBuffers {
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;
    @Unique private VertexBuffer[] overhaul$byLayer;
    @Unique private BlazeMesh[] overhaul$meshes;
    @Unique private volatile long overhaul$contents;
    @Unique private int overhaul$originX, overhaul$originY, overhaul$originZ;

    /** A cancelled rebuild can finish beside a newer one, so the reference and its copy change together. */
    @WrapOperation(method = {"reset", "setCompiled"}, at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicReference;set(Ljava/lang/Object;)V", remap = false), require = 2)
    private void overhaul$publish(AtomicReference<?> reference, Object compiled, Operation<Void> set) {
        synchronized (this) {
            set.call(reference, compiled);
            overhaul$contents = ((BlazeCompiledContents) compiled).overhaul$contents();
        }
        BlazeVisibleSections.changed();
    }

    @Inject(method = "setDirty", at = @At("TAIL"), require = 1)
    private void overhaul$markedDirty(boolean playerChanged, CallbackInfo callback) {
        BlazeVisibleSections.changed();
    }

    @Inject(method = "setOrigin", at = @At("TAIL"), require = 1)
    private void overhaul$keepOrigin(int x, int y, int z, CallbackInfo callback) {
        overhaul$originX = x;
        overhaul$originY = y;
        overhaul$originZ = z;
        BlazeVisibleSections.changed();
    }

    @Override public long overhaul$contents() { return overhaul$contents; }
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
