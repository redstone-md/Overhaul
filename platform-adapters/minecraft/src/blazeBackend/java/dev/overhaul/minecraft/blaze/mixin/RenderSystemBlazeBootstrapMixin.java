package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native limits and presentation replace GL initialization; original event polling and render-call replay remain. */
@Mixin(value=RenderSystem.class, remap=false)
public abstract class RenderSystemBlazeBootstrapMixin {
    @Shadow private static String apiDescription;
    @Shadow private static int MAX_SUPPORTED_TEXTURE_SIZE;
    /** The original body runs against the GL boundary so other mods' hooks on this method still fire. */
    @Inject(method="initRenderer",at=@At("RETURN"))
    private static void overhaul$initializeVulkan(int debugVerbosity,boolean synchronousDebug,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer==null)return;
        RenderSystem.assertOnRenderThreadOrInit();apiDescription="Vulkan / "+renderer.context().deviceName();
        MAX_SUPPORTED_TEXTURE_SIZE=renderer.context().capabilities().maxImageDimension2D();
    }
    /** Mods stitch atlases on worker threads, where the Vulkan context cannot be touched; the limit is read once at startup. */
    @Inject(method="maxSupportedTextureSize",at=@At("HEAD"),cancellable=true)
    private static void overhaul$nativeTextureLimit(CallbackInfoReturnable<Integer> callback){
        if(BlazeFrontend.current()!=null)callback.setReturnValue(MAX_SUPPORTED_TEXTURE_SIZE);
    }
    @Inject(method="getCapsString",at=@At("HEAD"),cancellable=true)
    private static void overhaul$nativeDescription(CallbackInfoReturnable<String> callback){
        var renderer=BlazeFrontend.current();if(renderer!=null)callback.setReturnValue("Vulkan / "+renderer.context().deviceName());
    }
    @WrapOperation(method="flipFrame",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"),require=1,allow=1)
    private static void overhaul$present(long window,Operation<Void> original){
        var renderer=BlazeFrontend.current();if(renderer==null)original.call(window);else renderer.present();
    }
}
