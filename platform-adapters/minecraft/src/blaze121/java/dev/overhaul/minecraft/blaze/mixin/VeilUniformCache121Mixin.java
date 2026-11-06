package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeVeilPrograms;
import foundry.veil.api.client.render.shader.program.ShaderUniformCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.function.IntSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Veil learns a program's uniforms and blocks from reflection of its SPIR-V; the OpenGL queries would answer nothing. */
@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.shader.program.ShaderUniformCache", remap = false)
abstract class VeilUniformCache121Mixin {
    @Shadow(remap = false) @Final private IntSupplier shader;
    @Shadow(remap = false) @Final private Object2ObjectMap<String, ShaderUniformCache.Uniform> samplers;
    @Shadow(remap = false) @Final private Object2ObjectMap<String, ShaderUniformCache.Uniform> uniforms;
    @Shadow(remap = false) @Final private Object2ObjectMap<String, ShaderUniformCache.UniformBlock> uniformBlocks;
    @Shadow(remap = false) private boolean requested;

    @Inject(method = "updateUniforms", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$reflect(CallbackInfo callback) {
        if (BlazeVeilPrograms.fill(shader.getAsInt(), uniforms, samplers, uniformBlocks)) {
            requested = true;
            callback.cancel();
        }
    }
}
