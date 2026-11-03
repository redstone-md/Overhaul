package dev.overhaul.minecraft.particle;

/** Instance-owned feature renderer, nullable before registration or when a different implementation is installed. */
public interface ParticleRendererAccess {
    OverhaulQuadParticleRenderer overhaul$particles();
}
