package dev.overhaul.minecraft.blaze;

/** A vertex buffer that hands out room for whole particle quads. */
public interface BlazeParticleVertices {
    /** Bytes per particle vertex: position, texture coordinates, colour and light. */
    int PARTICLE_STRIDE = 28;

    /** Address of {@code count} finished vertices in the particle layout, or 0 when the buffer holds another layout. */
    long overhaul$reserveParticleVertices(int count);
}
