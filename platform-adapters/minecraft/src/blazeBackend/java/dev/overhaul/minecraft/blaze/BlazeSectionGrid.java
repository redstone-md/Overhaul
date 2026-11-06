package dev.overhaul.minecraft.blaze;

/** The view area's section grid, indexed {@code (z * height + y) * width + x}, so a column's sections are {@code width} apart. */
public interface BlazeSectionGrid {
    int overhaul$gridWidth();
    int overhaul$gridHeight();
    int overhaul$gridDepth();
}
