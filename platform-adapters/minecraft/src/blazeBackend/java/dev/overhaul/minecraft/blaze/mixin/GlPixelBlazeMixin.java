package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public abstract class GlPixelBlazeMixin {
    @Inject(method="_pixelStore",at=@At("HEAD"),cancellable=true)
    private static void overhaul$store(int name,int value,CallbackInfo callback){var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().pixelStore(name,value);callback.cancel();}}
    @Inject(method="_readPixels(IIIIIILjava/nio/ByteBuffer;)V",at=@At("HEAD"),cancellable=true)
    private static void overhaul$readBuffer(int x,int y,int width,int height,int format,int type,ByteBuffer pixels,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().read(renderer.readTarget(),x,y,width,height,format,type,pixels);callback.cancel();}
    }
    @Inject(method="_readPixels(IIIIIIJ)V",at=@At("HEAD"),cancellable=true)
    private static void overhaul$readAddress(int x,int y,int width,int height,int format,int type,long pixels,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().read(renderer.readTarget(),x,y,width,height,format,type,pixels);callback.cancel();}
    }
    @Inject(method="_glCopyTexSubImage2D",at=@At("HEAD"),cancellable=true)
    private static void overhaul$copy(int target,int level,int dx,int dy,int x,int y,int width,int height,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().copy(renderer.readTarget(),target,level,dx,dy,x,y,width,height);callback.cancel();}
    }
    /** The IntBuffer overload bypasses _texSubImage2D; retain its exact original finally/consumer cleanup. */
    @WrapOperation(method="_upload",at=@At(value="INVOKE",target="Lorg/lwjgl/opengl/GL11;glTexSubImage2D(IIIIIIIILjava/nio/IntBuffer;)V"),require=1,allow=1)
    private static void overhaul$uploadInts(int target,int level,int x,int y,int width,int height,int format,int type,IntBuffer pixels,Operation<Void> original){
        var renderer=BlazeFrontend.current();
        if(renderer==null)original.call(target,level,x,y,width,height,format,type,pixels);
        else renderer.textures().upload(target,level,x,y,width,height,format,type,MemoryUtil.memByteBuffer(pixels));
    }
    @Inject(method="_glDrawPixels",at=@At("HEAD"))
    private static void overhaul$rejectRasterPixels(int width,int height,int format,int type,long pixels,CallbackInfo callback){
        if(BlazeFrontend.current()!=null)throw new UnsupportedOperationException("Legacy raster pixel draws have no owned Vulkan shader/geometry path");
    }
}
