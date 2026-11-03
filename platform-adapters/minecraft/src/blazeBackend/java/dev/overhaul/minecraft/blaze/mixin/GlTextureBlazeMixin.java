package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The shared game upload paths remain intact; only device calls enter the owned texture namespace. */
@Mixin(GlStateManager.class)
public abstract class GlTextureBlazeMixin {
    @Inject(method="_genTexture",at=@At("HEAD"),cancellable=true)
    private static void overhaul$generate(CallbackInfoReturnable<Integer> result){var renderer=BlazeFrontend.current();if(renderer!=null)result.setReturnValue(renderer.textures().generate());}
    @Inject(method="_genTextures",at=@At("HEAD"),cancellable=true)
    private static void overhaul$generateMany(int[] ids,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer==null)return;
        for(int index=0;index<ids.length;index++)ids[index]=renderer.textures().generate();callback.cancel();
    }
    @Inject(method="_deleteTexture",at=@At("HEAD"),cancellable=true)
    private static void overhaul$delete(int id,CallbackInfo callback){var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().delete(id);callback.cancel();}}
    @Inject(method="_deleteTextures",at=@At("HEAD"),cancellable=true)
    private static void overhaul$deleteMany(int[] ids,CallbackInfo callback){var renderer=BlazeFrontend.current();if(renderer!=null){for(int id:ids)renderer.textures().delete(id);callback.cancel();}}
    @Inject(method="_bindTexture",at=@At("HEAD"),cancellable=true)
    private static void overhaul$bind(int id,CallbackInfo callback){var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().bind(id);callback.cancel();}}
    @Inject(method={"_activeTexture","glActiveTexture"},at=@At("HEAD"),cancellable=true)
    private static void overhaul$active(int unit,CallbackInfo callback){var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().active(unit);callback.cancel();}}
    @Inject(method="_getActiveTexture",at=@At("HEAD"),cancellable=true)
    private static void overhaul$currentUnit(CallbackInfoReturnable<Integer> result){var renderer=BlazeFrontend.current();if(renderer!=null)result.setReturnValue(renderer.textures().active());}
    @Inject(method="_texParameter(III)V",at=@At("HEAD"),cancellable=true)
    private static void overhaul$parameterInt(int target,int name,int value,CallbackInfo callback){var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().parameter(target,name,value);callback.cancel();}}
    @Inject(method="_texParameter(IIF)V",at=@At("HEAD"),cancellable=true)
    private static void overhaul$parameterFloat(int target,int name,float value,CallbackInfo callback){var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().parameter(target,name,value);callback.cancel();}}
    @Inject(method="_getTexLevelParameter",at=@At("HEAD"),cancellable=true)
    private static void overhaul$level(int target,int level,int name,CallbackInfoReturnable<Integer> result){var renderer=BlazeFrontend.current();if(renderer!=null)result.setReturnValue(renderer.textures().levelParameter(target,level,name));}
    @Inject(method="_texImage2D",at=@At("HEAD"),cancellable=true)
    private static void overhaul$image(int target,int level,int internal,int width,int height,int border,int format,int type,IntBuffer pixels,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().image(target,level,internal,width,height,border,format,type,pixels);callback.cancel();}
    }
    @Inject(method="_texSubImage2D",at=@At("HEAD"),cancellable=true)
    private static void overhaul$subImage(int target,int level,int x,int y,int width,int height,int format,int type,long pixels,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().upload(target,level,x,y,width,height,format,type,pixels);callback.cancel();}
    }
    @Inject(method="_getTexImage",at=@At("HEAD"),cancellable=true)
    private static void overhaul$download(int target,int level,int format,int type,long pixels,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().download(target,level,format,type,pixels);callback.cancel();}
    }
}
