package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeVeilAccess;
import dev.overhaul.minecraft.blaze.BlazeVeilPrograms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Releases a captured stage source with its shader. */
@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.shader.compiler.CompiledShader", remap = false)
abstract class VeilCompiledShader121Mixin implements BlazeVeilAccess.Stage {
    @Shadow(remap = false) @Final private int id;

    @Override
    public int overhaul$id() {
        return id;
    }

    @Inject(method = "free", at = @At("HEAD"), require = 0)
    private void overhaul$forget(CallbackInfo callback) {
        BlazeVeilPrograms.forget(id);
    }
}
