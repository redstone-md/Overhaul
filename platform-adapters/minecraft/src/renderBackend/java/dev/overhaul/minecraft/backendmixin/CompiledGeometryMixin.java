package dev.overhaul.minecraft.backendmixin;

import dev.overhaul.core.culling.TerrainGeometry;
import dev.overhaul.minecraft.terrain.TerrainGeometryAccess;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledGeometryMixin implements TerrainGeometryAccess {
    @Unique private TerrainGeometry overhaul$geometry;
    @Inject(method="<init>",at=@At("RETURN"))
    private void overhaul$copy(TranslucencyPointOfView view,SectionCompiler.Results results,long started,CallbackInfo callback){
        overhaul$geometry=((TerrainGeometryAccess)(Object)results).overhaul$geometry();
    }
    @Override public TerrainGeometry overhaul$geometry(){return overhaul$geometry;}
    @Override public void overhaul$geometry(TerrainGeometry geometry){throw new UnsupportedOperationException("Installed geometry is immutable");}
}
