package dev.overhaul.minecraft.blaze;

import org.joml.FrustumIntersection;

/** A particle that tests its own render box against the view without building the box first. */
public interface BlazeParticleBox {
    /** True when the box vanilla would build for this frame is in view; same numbers as Frustum.isVisible. */
    boolean overhaul$inView(FrustumIntersection view, double cameraX, double cameraY, double cameraZ, float partialTick);

    /**
     * Writes the centre relative to the anchor and a half size covering every box vanilla can build for this particle
     * until its next tick, as four floats from {@code at}.
     */
    void overhaul$bounds(float[] boxes, int at, double anchorX, double anchorY, double anchorZ);
}
