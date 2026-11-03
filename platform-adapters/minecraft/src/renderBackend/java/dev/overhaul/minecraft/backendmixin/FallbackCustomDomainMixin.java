package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.model.ModelSubmitDomainAccess;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Domain follows the original custom callback; its vertex format remains authoritative. */
@Mixin(CustomFeatureRenderer.class)
public abstract class FallbackCustomDomainMixin {
    @WrapOperation(method = "buildGroup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/CustomFeatureRenderer$Submit;renderType()Lnet/minecraft/client/renderer/rendertype/RenderType;"),
            require = 1, allow = 1)
    private RenderType overhaul$domainType(CustomFeatureRenderer.Submit submit, Operation<RenderType> original) {
        return ModelSubmitDomainAccess.renderType(original.call(submit), submit);
    }
}
