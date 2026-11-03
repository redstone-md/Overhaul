package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.overhaul.minecraft.model.DomainRenderTypeAccess;
import dev.overhaul.minecraft.model.ModelSubmitDomainAccess;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The original model builder retains domain even when instanced capture is declined. */
@Mixin(ModelFeatureRenderer.class)
public abstract class FallbackModelDomainMixin {
    @WrapOperation(method = "prepareModel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"), require = 1)
    private VertexConsumer overhaul$domainBuffer(ModelFeatureRenderer renderer, RenderType type,
            Operation<VertexConsumer> original, ModelFeatureRenderer.Submit<?> submit) {
        RenderType variant = ((DomainRenderTypeAccess) type).overhaul$forDomain(ModelSubmitDomainAccess.domain(submit));
        return original.call(renderer, variant);
    }
}
