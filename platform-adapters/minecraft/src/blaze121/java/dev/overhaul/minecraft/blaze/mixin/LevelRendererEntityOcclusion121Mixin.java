package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeSectionOcclusion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips entities recent terrain depth proves are behind something. Terrain is the only occluder and the test takes the
 * entity's position now, so a moving entity cannot be hidden by its own old place. Anything that can show beyond its box
 * or through walls keeps vanilla's answer; a mod's beam or oversized model on an ordinary entity is not known here.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererEntityOcclusion121Mixin {
    @Shadow @Final private Minecraft minecraft;

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean overhaul$hideBehindTerrain(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum, double x, double y, double z, Operation<Boolean> original) {
        if (!original.call(dispatcher, entity, frustum, x, y, z)) return false;
        BlazeRenderer renderer = BlazeFrontend.current();
        BlazeSectionOcclusion occlusion = renderer == null ? null : renderer.occlusion();
        if (occlusion == null || overhaul$showsPastItsBox(entity)) return true;
        occlusion.prepare(x, y, z);
        AABB box = entity.getBoundingBoxForCulling().inflate(1 + entity.getDeltaMovement().length());
        return !occlusion.entityHidden(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private boolean overhaul$showsPastItsBox(Entity entity) {
        return entity.noCulling || entity.shouldShowName() || minecraft.shouldEntityAppearGlowing(entity) || entity.hasCustomOutlineRendering(minecraft.player)
                || entity instanceof Guardian || entity instanceof EndCrystal || entity instanceof Leashable leashable && leashable.isLeashed();
    }
}
