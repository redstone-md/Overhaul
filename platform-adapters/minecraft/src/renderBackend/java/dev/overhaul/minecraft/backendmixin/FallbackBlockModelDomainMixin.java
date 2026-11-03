package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.model.ModelSubmitDomainAccess;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Original block-quad/decal writers receive a domain-separated prepared draw. */
@Mixin(BlockModelFeatureRenderer.class)
public abstract class FallbackBlockModelDomainMixin {
    @WrapOperation(method = "buildGroup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/BlockModelFeatureRenderer$Submit;renderType()Lnet/minecraft/client/renderer/rendertype/RenderType;"),
            require = 1, allow = 1)
    private RenderType overhaul$domainType(BlockModelFeatureRenderer.Submit submit, Operation<RenderType> original) {
        return ModelSubmitDomainAccess.renderType(original.call(submit), submit);
    }
}
