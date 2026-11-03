package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.model.ModelSubmitDomainAccess;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Retains ordinary quad, special foil UV3 and outline emission without duplicating writers. */
@Mixin(ItemFeatureRenderer.class)
public abstract class FallbackItemDomainMixin {
    @WrapOperation(method = {"prepareMainSubmit", "prepareOutlineSubmit"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 3, allow = 3)
    private VertexConsumer overhaul$domainBuffer(ItemFeatureRenderer renderer, RenderType type,
            Operation<VertexConsumer> original, ItemFeatureRenderer.Submit submit) {
        return original.call(renderer, ModelSubmitDomainAccess.renderType(type, submit));
    }
}
