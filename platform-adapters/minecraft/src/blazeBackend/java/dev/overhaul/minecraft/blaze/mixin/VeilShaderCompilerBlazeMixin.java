package dev.overhaul.minecraft.blaze.mixin;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Veil relinks every vanilla shader through raw OpenGL so it can add dynamic buffer outputs, then swaps the program
 * name inside each ShaderInstance. Those names have no Vulkan program, so the swap would leave every core shader dead.
 * The programs linked by the frontend stay in place; Veil's dynamic buffers receive no shader output on this backend.
 */
@Pseudo
@Mixin(targets="foundry.veil.impl.client.render.dynamicbuffer.VanillaShaderCompiler", remap=false)
public abstract class VeilShaderCompilerBlazeMixin {
    @Inject(method="reload", at=@At("HEAD"), cancellable=true, require=0)
    private void overhaul$keepNativePrograms(Collection<?> shaders, CallbackInfoReturnable<CompletableFuture<?>> callback) {
        callback.setReturnValue(CompletableFuture.completedFuture(null));
    }
}
