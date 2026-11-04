package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazeFormatAccess;
import dev.overhaul.minecraft.blaze.BlazeGameMesh;
import dev.overhaul.minecraft.blaze.BlazeMeshState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(VertexFormat.class)
public abstract class VertexFormatBlazeMixin implements BlazeFormatAccess {
    @Unique private BlazeMeshState.Layout overhaul$layout;

    @Override public synchronized BlazeMeshState.Layout overhaul$layout() {
        if (overhaul$layout == null) overhaul$layout = BlazeGameMesh.layout((VertexFormat) (Object) this);
        return overhaul$layout;
    }
}
