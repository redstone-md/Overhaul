package dev.overhaul.minecraft.backendmixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.particle.OverhaulQuadParticleRenderer;
import dev.overhaul.minecraft.particle.ParticleRendererAccess;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Normal feature registration retains its phase/texture/lifecycle dispatch, replacing quad expansion. */
@Mixin(FeatureRenderDispatcher.class)
public abstract class InstancedParticleRendererMixin implements ParticleRendererAccess {
    @Shadow @Final private FeatureRendererMap featureRenderers;
    @Override public OverhaulQuadParticleRenderer overhaul$particles(){
        var renderer=featureRenderers.get(QuadParticleFeatureRenderer.TYPE);
        return renderer instanceof OverhaulQuadParticleRenderer owned?owned:null;
    }
    @WrapOperation(method="<init>",at=@At(value="NEW",target="()Lnet/minecraft/client/renderer/feature/QuadParticleFeatureRenderer;"))
    private QuadParticleFeatureRenderer overhaul$particles(Operation<QuadParticleFeatureRenderer> original) {
        return new OverhaulQuadParticleRenderer();
    }
}
