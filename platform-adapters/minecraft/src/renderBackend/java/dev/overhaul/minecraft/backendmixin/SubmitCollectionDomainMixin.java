package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.model.ModelSubmitDomainAccess;
import dev.overhaul.minecraft.model.SubmitDomainCollectionAccess;
import dev.overhaul.minecraft.model.SubmitDomainState;
import java.util.Objects;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** One capture boundary covers actual model, block-model, item and custom geometry nodes. */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitCollectionDomainMixin implements SubmitDomainCollectionAccess {
    @Unique private SubmitDomainState overhaul$domains = new SubmitDomainState();
    @Override public SubmitDomainState overhaul$submitDomains() { return overhaul$domains; }
    @Override public void overhaul$bindSubmitDomains(SubmitDomainState domains) { overhaul$domains = Objects.requireNonNull(domains); }

    @WrapOperation(method = {"submitModel", "submitCrumblingOverlay", "submitBlockModel",
            "submitBreakingBlockModel", "submitItem", "submitCustomGeometry"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"),
            require = 12, allow = 12)
    private void overhaul$tagSimple(SimpleFeatureRenderPhase<?> phase, SubmitNode submit, Operation<Void> original) {
        ModelSubmitDomainAccess.capture(submit, overhaul$domains.current());
        original.call(phase, submit);
    }

    @WrapOperation(method = {"submitModel", "submitCrumblingOverlay", "submitBlockModel",
            "submitBreakingBlockModel", "submitItem", "submitCustomGeometry"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"),
            require = 3, allow = 3)
    private void overhaul$tagPhase(FeatureRenderPhase<?> phase, SubmitNode submit, Operation<Void> original) {
        ModelSubmitDomainAccess.capture(submit, overhaul$domains.current());
        original.call(phase, submit);
    }
}
