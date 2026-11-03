package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.MinecraftBlazeAccess;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.glfw.GLFW.*;

/** Keeps the game's real window callbacks/fullscreen handling while owning its Vulkan device and lifetime. */
@Mixin(Window.class)
public abstract class WindowBlazeMixin {
    @Shadow @Final private long window;
    @Shadow @Final private GLFWErrorCallback defaultErrorCallback;
    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;
    @Unique private boolean overhaul$blazeRequested;
    @Unique private BlazeRenderer overhaul$blazeRenderer;

    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwDefaultWindowHints()V"),require=1,allow=1)
    private void overhaul$captureSelection(Operation<Void> original){overhaul$blazeRequested=BlazeFrontend.requested();original.call();}
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void overhaul$noApiHints(int hint,int value,Operation<Void> original){
        if(!overhaul$blazeRequested){original.call(hint,value);return;}
        if(hint==GLFW_CLIENT_API){original.call(hint,GLFW_NO_API);return;}
        if(hint==GLFW_CONTEXT_VERSION_MAJOR||hint==GLFW_CONTEXT_VERSION_MINOR||hint==GLFW_OPENGL_PROFILE
                ||hint==GLFW_OPENGL_FORWARD_COMPAT||hint==GLFW_CONTEXT_CREATION_API)return;
        original.call(hint,value);
    }
    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"),require=1,allow=1)
    private void overhaul$noGlContext(long handle,Operation<Void> original){if(!overhaul$blazeRequested)original.call(handle);}

    @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"),require=1,allow=1)
    private GLCapabilities overhaul$installDevice(Operation<GLCapabilities> original){
        if(!overhaul$blazeRequested)return original.call();
        Minecraft client=Minecraft.getInstance();if(client==null){overhaul$releaseFailedWindow();throw new IllegalStateException("Blaze3D Vulkan initialization requires its owning Minecraft instance");}
        var owner=(MinecraftBlazeAccess)client;
        if(owner.overhaul$blazeRenderer()!=null){overhaul$releaseFailedWindow();throw new IllegalStateException("A client can own only one Blaze3D Vulkan window");}
        try(MemoryStack stack=MemoryStack.stackPush()){
            var width=stack.mallocInt(1);var height=stack.mallocInt(1);glfwGetFramebufferSize(window,width,height);
            var renderer=new BlazeRenderer(window,Math.max(1,width.get(0)),Math.max(1,height.get(0)));
            try{renderer.pendingWindowCleanup(this::overhaul$releaseFailedWindow);owner.overhaul$installBlazeRenderer(renderer);overhaul$blazeRenderer=renderer;renderer.resize(width.get(0),height.get(0));}
            catch(RuntimeException|Error failure){
                overhaul$blazeRenderer=null;owner.overhaul$removeBlazeRenderer(renderer);
                try{renderer.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
            }
        }catch(RuntimeException|Error failure){
            try{overhaul$releaseFailedWindow();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
        // The original constructor discards the return value; no OpenGL capability object exists in this mode.
        return null;
    }
    @Inject(method="<init>",at=@At("RETURN"))
    private void overhaul$applyFinalWindowMode(CallbackInfo callback){
        if(overhaul$blazeRenderer!=null){overhaul$blazeRenderer.resize(framebufferWidth,framebufferHeight);overhaul$blazeRenderer.windowReady();}
    }
    @Unique private void overhaul$releaseFailedWindow(){
        try{
            Callbacks.glfwFreeCallbacks(window);var installed=glfwSetErrorCallback(null);
            boolean same=installed!=null&&installed.address()==defaultErrorCallback.address();
            try{if(installed!=null)installed.free();}finally{if(!same)defaultErrorCallback.free();}
        }finally{glfwDestroyWindow(window);}
    }
    @WrapOperation(method="updateVsync",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwSwapInterval(I)V"),require=1,allow=1)
    private void overhaul$presentationPolicy(int interval,Operation<Void> original){
        if(overhaul$blazeRenderer==null)original.call(interval);else overhaul$blazeRenderer.presentMode(interval!=0);
    }
    @Inject(method="onFramebufferResize",at=@At("RETURN"))
    private void overhaul$resizeSurface(long handle,int width,int height,CallbackInfo callback){
        if(handle==window&&overhaul$blazeRenderer!=null)overhaul$blazeRenderer.resize(width,height);
    }
    @WrapMethod(method="close")
    private void overhaul$closeDevice(Operation<Void> original){
        BlazeRenderer renderer=overhaul$blazeRenderer;overhaul$blazeRenderer=null;Throwable failure=null;
        try{if(renderer!=null)renderer.close();}catch(RuntimeException|Error error){failure=error;}
        finally{Minecraft client=Minecraft.getInstance();if(client instanceof MinecraftBlazeAccess owner)owner.overhaul$removeBlazeRenderer(renderer);}
        try{original.call();}catch(RuntimeException|Error error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;
    }
}
