package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeSectionOcclusion;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.CompiledChunk;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives vanilla an empty compile for a chunk recent terrain depth hides, so its block entities are skipped. Only the
 * block-entity loop reads the compile here; the global list, where beacon beams and the like live, keeps drawing.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererBlockEntityOcclusion120Mixin {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk;getCompiledChunk()Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk;"))
    private CompiledChunk overhaul$hideBehindTerrain(RenderChunk chunk, Operation<CompiledChunk> original, @Local(argsOnly = true) Camera camera) {
        CompiledChunk compiled = original.call(chunk);
        if (compiled.getRenderableBlockEntities().isEmpty()) return compiled;
        BlazeRenderer renderer = BlazeFrontend.current();
        BlazeSectionOcclusion occlusion = renderer == null ? null : renderer.occlusion();
        if (occlusion == null) return compiled;
        Vec3 eye = camera.getPosition();
        occlusion.prepare(eye.x, eye.y, eye.z);
        var origin = chunk.getOrigin();
        return occlusion.blockEntitiesHidden(origin.getX(), origin.getY(), origin.getZ()) ? CompiledChunk.UNCOMPILED : compiled;
    }
}
