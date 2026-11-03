package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.minecraft.particle.QuadParticleStateAccess;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.Map;
import java.util.Objects;

@Mixin(QuadParticleRenderState.class)
public abstract class QuadParticleStateMixin implements QuadParticleStateAccess {
    @Shadow @Final private Map<SingleQuadParticle.Layer,?> particles;
    @Shadow private int particleCount;

    @Override public int overhaul$count(SingleQuadParticle.Layer layer) {
        Object storage=particles.get(Objects.requireNonNull(layer,"layer"));
        return storage==null?0:((ParticleStorageAccess)storage).overhaul$count();
    }
    @Override public int overhaul$totalCount(){return particleCount;}
    @Override public void overhaul$forEachParticle(SingleQuadParticle.Layer layer,QuadParticleRenderState.ParticleConsumer consumer) {
        Objects.requireNonNull(consumer,"consumer");
        Object storage=particles.get(Objects.requireNonNull(layer,"layer"));
        if(storage!=null)((ParticleStorageAccess)storage).overhaul$forEachParticle(consumer);
    }
}
