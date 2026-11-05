package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeSectionOcclusion;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives vanilla no block entities for a section recent terrain depth hides. This list holds only those whose renderers
 * stay near their block; ones that draw far past it, like beacon beams, are in the global list and keep drawing.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererBlockEntityOcclusion121Mixin {
    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$CompiledSection;getRenderableBlockEntities()Ljava/util/List;"))
    private List<BlockEntity> overhaul$hideBehindTerrain(List<BlockEntity> blockEntities, @Local RenderSection section, @Local(argsOnly = true) Camera camera) {
        if (blockEntities.isEmpty()) return blockEntities;
        BlazeRenderer renderer = BlazeFrontend.current();
        BlazeSectionOcclusion occlusion = renderer == null ? null : renderer.occlusion();
        if (occlusion == null) return blockEntities;
        Vec3 eye = camera.getPosition();
        occlusion.prepare(eye.x, eye.y, eye.z);
        var origin = section.getOrigin();
        return occlusion.blockEntitiesHidden(origin.getX(), origin.getY(), origin.getZ()) ? List.of() : blockEntities;
    }
}
