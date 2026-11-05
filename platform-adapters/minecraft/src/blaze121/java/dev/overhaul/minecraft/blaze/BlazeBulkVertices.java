package dev.overhaul.minecraft.blaze;

/** A vertex builder that hands out one contiguous range for a whole model cube. */
public interface BlazeBulkVertices {
    /** Returns the address of {@code count} entity vertices, or 0 when this builder's layout differs. */
    long overhaul$reserveEntityVertices(int count);
}
