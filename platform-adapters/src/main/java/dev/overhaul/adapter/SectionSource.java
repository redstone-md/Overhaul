package dev.overhaul.adapter;

/** Borrowed live-world view. All calls occur synchronously on the bridge owner. */
public interface SectionSource {
    /** Zero for air/non-cube geometry; positive 24-bit material for opaque cubes. */
    int material(int blockX, int blockY, int blockZ);
    int emission(int blockX, int blockY, int blockZ);
}
