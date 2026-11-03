package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.core.culling.TerrainGeometry;
import dev.overhaul.minecraft.terrain.TerrainGeometryAccess;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionCompiler.Results.class)
public abstract class SectionGeometryMixin implements TerrainGeometryAccess {
    @Unique private TerrainGeometry overhaul$geometry;
    @Override public TerrainGeometry overhaul$geometry(){return overhaul$geometry;}
    @Override public void overhaul$geometry(TerrainGeometry geometry){
        if(overhaul$geometry!=null)throw new IllegalStateException("Geometry metadata already assigned");
        overhaul$geometry=java.util.Objects.requireNonNull(geometry);
    }
}
