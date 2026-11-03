package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.EffectProgram;
import com.mojang.blaze3d.shaders.Program;
import dev.overhaul.minecraft.blaze.BlazePrograms;
import dev.overhaul.minecraft.blaze.BlazeStageAccess;
import java.io.IOException;
import java.io.InputStream;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EffectProgram.class)
public abstract class EffectProgramBlazeMixin {
    @Shadow @Final private static GlslPreprocessor PREPROCESSOR;
    @Inject(method="compileShader",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$compile(Program.Type type,String name,InputStream input,String pack,CallbackInfoReturnable<EffectProgram> callback)throws IOException{
        var programs=BlazePrograms.current();if(programs!=null)callback.setReturnValue((EffectProgram)programs.stage(type,name,input,PREPROCESSOR,true));
    }
    @Inject(method="close",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$close(CallbackInfo callback){if(((BlazeStageAccess)this).overhaul$stage()!=null)callback.cancel();}
}
