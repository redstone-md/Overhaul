package dev.overhaul.minecraft.blaze;

import org.joml.FrustumIntersection;

/** A particle that tests its own render box against the view without building the box first. */
public interface BlazeParticleBox {
    /** True when the box vanilla would build for this frame is in view; same numbers as Frustum.isVisible. */
    boolean overhaul$inView(FrustumIntersection view, double cameraX, double cameraY, double cameraZ, float partialTick);
}
