package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.lwjgl.opengl.GL40C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Veil's patch render state sets the OpenGL patch size. This backend has no OpenGL context: a tessellated program
 * reads each four-vertex patch as one lines-with-adjacency primitive, so the size is fixed and the call is dropped.
 */
@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.pipeline.PatchStateShard", remap = false)
abstract class VeilPatchState121Mixin {
    @Redirect(method = {"lambda$new$0", "lambda$new$1"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL40C;glPatchParameteri(II)V"), require = 0)
    private static void overhaul$patchSize(int name, int value) {
        if (!BlazeFrontend.requested()) GL40C.glPatchParameteri(name, value);
    }
}
