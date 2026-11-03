package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.Shader;
import dev.overhaul.minecraft.blaze.BlazePrograms;
import dev.overhaul.minecraft.blaze.BlazeStageAccess;
import java.io.IOException;
import java.io.InputStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Program.class)
public abstract class ProgramBlazeMixin implements BlazeStageAccess {
    @Unique private Stage overhaul$source;
    @Override public Stage overhaul$stage(){return overhaul$source;}
    @Override public void overhaul$stage(Stage stage){if(overhaul$source!=null)throw new IllegalStateException("Stage source already assigned");overhaul$source=stage;}
    @Inject(method="compileShader",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$compile(Program.Type type,String name,InputStream input,String pack,GlslPreprocessor preprocessor,CallbackInfoReturnable<Program> callback)throws IOException{
        var programs=BlazePrograms.current();if(programs!=null)callback.setReturnValue(programs.stage(type,name,input,preprocessor,false));
    }
    @Inject(method="attachToShader",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$attach(Shader shader,CallbackInfo callback){if(overhaul$source!=null)callback.cancel();}
    @Inject(method="close",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$close(CallbackInfo callback){if(overhaul$source!=null)callback.cancel();}
}
