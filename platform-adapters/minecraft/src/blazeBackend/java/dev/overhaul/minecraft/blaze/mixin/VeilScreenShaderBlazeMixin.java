package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Veil's shortcuts for the screen blit and the in-wall overlay draw a raw triangle, which reaches nothing on Vulkan.
 * Both step aside when their shader is missing, which leaves the vanilla paths that do render.
 */
@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.VeilRenderSystem", remap = false)
abstract class VeilScreenShaderBlazeMixin {
    @Inject(method = "setShader(Lnet/minecraft/resources/ResourceLocation;)Lfoundry/veil/api/client/render/shader/program/ShaderProgram;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$vanillaPath(ResourceLocation shader, CallbackInfoReturnable<Object> callback) {
        if (BlazeFrontend.current() == null || !"veil".equals(shader.getNamespace())) return;
        String path = shader.getPath();
        if (path.equals("blit_screen") || path.equals("core/blit_screen_effect")) callback.setReturnValue(null);
    }
}
