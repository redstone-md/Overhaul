package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.overhaul.minecraft.model.ModelSubmitDomainAccess;
import dev.overhaul.minecraft.model.SubmitDomainCollectionAccess;
import dev.overhaul.minecraft.model.SubmitDomainState;
import java.util.Objects;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Tag the real deferred nodes, including the game's additional outline/decal submits. */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitCollectionDomainMixin implements SubmitDomainCollectionAccess {
    @Unique private SubmitDomainState overhaul$domains = new SubmitDomainState();
    @Override public SubmitDomainState overhaul$submitDomains() { return overhaul$domains; }
    @Override public void overhaul$bindSubmitDomains(SubmitDomainState domains) { overhaul$domains = Objects.requireNonNull(domains); }

    @WrapOperation(method = {"submitModel", "submitCrumblingOverlay"}, at = @At(value = "NEW",
            target = "net/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit"), require = 3, allow = 3)
    private ModelFeatureRenderer.Submit<?> overhaul$tagModel(RenderType type, PoseStack.Pose pose,
            Model<?> model, Object state, int light, int overlay, int color, UvMapping mapping,
            PoseStack.Pose decal, Operation<ModelFeatureRenderer.Submit<?>> original) {
        var submit = original.call(type, pose, model, state, light, overlay, color, mapping, decal);
        ((ModelSubmitDomainAccess) (Object) submit).overhaul$sceneDomain(overhaul$domains.current());
        return submit;
    }
}
