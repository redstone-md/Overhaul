package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazeFormatAccess;
import dev.overhaul.minecraft.blaze.BlazeGameMesh;
import dev.overhaul.minecraft.blaze.BlazeMeshState;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VertexFormat.class)
public abstract class VertexFormatBlazeMixin implements BlazeFormatAccess {
    @Unique private BlazeMeshState.Layout overhaul$layout;

    /** Immediate draws are the game's streaming path; buffers mods create and keep stay in device memory. */
    @Inject(method="getImmediateDrawVertexBuffer",at=@At("RETURN"),require=1)
    private void overhaul$streamImmediate(CallbackInfoReturnable<VertexBuffer> callback) {
        ((BlazeVertexBufferAccess) callback.getReturnValue()).overhaul$streamUploads();
    }

    @Override public synchronized BlazeMeshState.Layout overhaul$layout() {
        if (overhaul$layout == null) overhaul$layout = BlazeGameMesh.layout((VertexFormat) (Object) this);
        return overhaul$layout;
    }
}
