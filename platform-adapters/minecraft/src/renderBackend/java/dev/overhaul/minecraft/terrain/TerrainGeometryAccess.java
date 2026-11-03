package dev.overhaul.minecraft.terrain;

import dev.overhaul.core.culling.TerrainGeometry;

/** Metadata travels from compiler results to its installed mesh, never through a world registry. */
public interface TerrainGeometryAccess {
    TerrainGeometry overhaul$geometry();
    void overhaul$geometry(TerrainGeometry geometry);
}
