package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Shader;
import dev.overhaul.minecraft.blaze.BlazePrograms;
import dev.overhaul.minecraft.blaze.BlazeStageAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ProgramManager.class)
public abstract class ProgramManagerBlazeMixin {
    @Inject(method="createProgram",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$create(CallbackInfoReturnable<Integer> callback){var programs=BlazePrograms.current();if(programs!=null)callback.setReturnValue(programs.create());}
    // Attaching still runs so mods that attach extra stages there (Lodestone geometry) reach the native link.
    @Inject(method="linkShader",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$link(Shader shader,CallbackInfo callback){var stage=((BlazeStageAccess)shader.getVertexProgram()).overhaul$stage();if(stage!=null){shader.attachToProgram();stage.owner().link(shader);callback.cancel();}}
    @Inject(method="releaseProgram",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$release(Shader shader,CallbackInfo callback){var stage=((BlazeStageAccess)shader.getVertexProgram()).overhaul$stage();if(stage!=null){stage.owner().release(shader.getId());shader.getVertexProgram().close();shader.getFragmentProgram().close();callback.cancel();}}
    @Inject(method="glUseProgram",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$use(int id,CallbackInfo callback){var programs=BlazePrograms.current();if(programs!=null){programs.use(id);callback.cancel();}}
}
