package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeLayerLists;
import dev.overhaul.minecraft.blaze.BlazeVisibleSections;
import dev.overhaul.minecraft.blaze.BlazeMesh;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeSectionBatch;
import dev.overhaul.minecraft.blaze.BlazeSectionOcclusion;
import dev.overhaul.minecraft.blaze.BlazeShaderAccess;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes the chunks away from the vanilla loop and draws them together once the shader's uniforms are final. The loop
 * still runs, on nothing, so what mods hook around it keeps its place; a mod hooking each chunk's draw sees none.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSectionBatch120Mixin {
    @Unique private ObjectListIterator<LevelRendererChunkInfo120Access> overhaul$batched;

    @ModifyExpressionValue(method = "renderChunkLayer", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;listIterator(I)Lit/unimi/dsi/fastutil/objects/ObjectListIterator;", remap = false))
    private ObjectListIterator<LevelRendererChunkInfo120Access> overhaul$takeChunks(ObjectListIterator<LevelRendererChunkInfo120Access> chunks) {
        overhaul$batched = null;
        BlazeRenderer renderer = BlazeFrontend.current();
        if (renderer == null ||!renderer.batchesSections(RenderSystem.getShader() instanceof BlazeShaderAccess shader ? shader : null)) return chunks;
        overhaul$batched = chunks;
        return ObjectLists.<LevelRendererChunkInfo120Access>emptyList().listIterator();
    }

    @Inject(method = "renderChunkLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V", shift = At.Shift.AFTER))
    private void overhaul$drawChunks(RenderType layer, PoseStack pose, double x, double y, double z, Matrix4f projection, CallbackInfo callback) {
        ObjectListIterator<LevelRendererChunkInfo120Access> chunks = overhaul$batched;
        if (chunks == null) return;
        overhaul$batched = null;
        boolean forward = layer != RenderType.translucent();
        BlazeRenderer renderer = BlazeFrontend.require();
        BlazeSectionBatch batch = renderer.beginSections((BlazeShaderAccess) RenderSystem.getShader(), !forward);
        BlazeSectionOcclusion capture = renderer.occlusion(), occlusion = capture;
        if (capture != null) {
            capture.prepare(x, y, z);
            if (capture.handTo(batch)) occlusion = null;
        }
        BlazeVisibleSections.Layer table = (Object) this instanceof BlazeLayerLists lists ? lists.overhaul$layerTable(layer) : null;
        boolean listed = (Object) this instanceof BlazeLayerLists lists && lists.overhaul$layerListed();
        if (table != null) {
            boolean flat = table.draws();
            for (int size = table.size(), at = 0; at < size; at++) {
                int index = forward ? at : size - 1 - at;
                BlazeMesh mesh = table.mesh(index);
                if (mesh == null) continue;
                int originX = table.originX(index), originY = table.originY(index), originZ = table.originZ(index);
                if (occlusion != null && occlusion.hidden(originX, originY, originZ)) continue;
                if (flat) batch.add(table, index, (float) (originX - x), (float) (originY - y), (float) (originZ - z));
                else batch.add(mesh, (float) (originX - x), (float) (originY - y), (float) (originZ - z));
            }
        } else while (forward ? chunks.hasNext() : chunks.hasPrevious()) {
            RenderChunk chunk = (forward ? chunks.next() : chunks.previous()).overhaul$chunk();
            if (!listed && chunk.getCompiledChunk().isEmpty(layer)) continue;
            BlazeMesh mesh = ((BlazeVertexBufferAccess) chunk.getBuffer(layer)).overhaul$meshOrNull();
            if (mesh == null) continue;
            var origin = chunk.getOrigin();
            if (occlusion != null && occlusion.hidden(origin.getX(), origin.getY(), origin.getZ())) continue;
            batch.add(mesh, (float) (origin.getX() - x), (float) (origin.getY() - y), (float) (origin.getZ() - z));
        }
        batch.end();
        // After the last opaque terrain layer and before anything that moves: only terrain may hide terrain.
        if (capture != null && layer == RenderType.cutout()) capture.capture(renderer.currentTarget(), pose.last().pose(), projection, x, y, z);
    }
}
