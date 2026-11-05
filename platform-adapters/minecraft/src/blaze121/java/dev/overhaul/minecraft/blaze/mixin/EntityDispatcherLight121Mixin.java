package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeLitEntity;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Two light lookups per entity per frame go through chunk and section maps; light seen up to one tick late does not show. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityDispatcherLight121Mixin {
    @WrapOperation(
            method = "getPackedLightCoords",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I"),
            require = 1)
    private int overhaul$oncePerTick(EntityRenderer<Entity> renderer, Entity entity, float partialTick, Operation<Integer> lookup) {
        BlazeLitEntity lit = (BlazeLitEntity) entity;
        long tick = entity.level().getGameTime();
        if (lit.overhaul$litAt() == tick) return lit.overhaul$light();
        int light = lookup.call(renderer, entity, partialTick);
        lit.overhaul$lit(tick, light);
        return light;
    }
}
