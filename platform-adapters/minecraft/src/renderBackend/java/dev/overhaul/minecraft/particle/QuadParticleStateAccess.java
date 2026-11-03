package dev.overhaul.minecraft.particle;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

/**
 * Synchronous read-only access to the current extracted particle snapshot.
 * Consume/copy during feature preparation: clear/extraction reuses its storage next frame.
 */
public interface QuadParticleStateAccess {
    int overhaul$count(SingleQuadParticle.Layer layer);
    int overhaul$totalCount();
    void overhaul$forEachParticle(SingleQuadParticle.Layer layer,QuadParticleRenderState.ParticleConsumer consumer);
}
