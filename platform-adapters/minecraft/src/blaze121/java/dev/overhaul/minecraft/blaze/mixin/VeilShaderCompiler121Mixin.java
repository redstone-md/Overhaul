package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeVeilAccess;
import dev.overhaul.minecraft.blaze.BlazeVeilPrograms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps each stage's finished source: OpenGL gets it as an inert call, the native compile needs it at link. */
@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.shader.compiler.DirectShaderCompiler", remap = false)
abstract class VeilShaderCompiler121Mixin {
    @Inject(method = "compile(ILfoundry/veil/api/client/render/shader/compiler/VeilShaderSource;)Lfoundry/veil/api/client/render/shader/compiler/CompiledShader;",
            at = @At("RETURN"), require = 0)
    private void overhaul$capture(int type, @Coerce Object source, CallbackInfoReturnable<Object> callback) {
        var access = (VeilShaderSource121Access) source;
        BlazeVeilPrograms.source(((BlazeVeilAccess.Stage) callback.getReturnValue()).overhaul$id(), type, String.valueOf(access.overhaul$sourceId()), access.overhaul$sourceCode());
    }
}
