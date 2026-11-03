package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import dev.overhaul.minecraft.blaze.BlazePrograms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Uniform.class)
public abstract class UniformBlazeMixin {
    @Shadow private boolean dirty;
    @Unique private boolean overhaul$owned,overhaul$closed;
    @Inject(method="<init>",at=@At("RETURN"),require=1)
    private void overhaul$created(String name,int type,int count,Shader shader,CallbackInfo callback){overhaul$owned=BlazePrograms.current()!=null;}
    @Inject(method="glGetUniformLocation",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$uniform(int id,CharSequence name,CallbackInfoReturnable<Integer> callback){var programs=BlazePrograms.current();if(programs!=null)callback.setReturnValue(programs.uniformLocation(id,name));}
    @Inject(method="glGetAttribLocation",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$attribute(int id,CharSequence name,CallbackInfoReturnable<Integer> callback){var programs=BlazePrograms.current();if(programs!=null)callback.setReturnValue(programs.attributeLocation(id,name));}
    @Inject(method="glBindAttribLocation",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$bind(int id,int location,CharSequence name,CallbackInfo callback){if(BlazePrograms.current()!=null)callback.cancel();}
    @Inject(method="uploadInteger",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$sampler(int location,int value,CallbackInfo callback){var programs=BlazePrograms.current();if(programs!=null){programs.samplerUnit(location,value);callback.cancel();}}
    @Inject(method="upload",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$upload(CallbackInfo callback){if(overhaul$owned){dirty=false;callback.cancel();}}
    @Inject(method="close",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$close(CallbackInfo callback){if(overhaul$owned){if(overhaul$closed)callback.cancel();overhaul$closed=true;}}
}
