package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.shaders.Uniform;
import dev.overhaul.minecraft.blaze.BlazeShaderAccess;
import dev.overhaul.minecraft.blaze.BlazePrograms;
import dev.overhaul.minecraft.blaze.BlazeStageAccess;
import dev.overhaul.minecraft.blaze.BlazeShaderSnapshot;
import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import java.util.Map;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceBlazeMixin implements BlazeShaderAccess {
    @Shadow @Final private Map<String, Uniform> uniformMap;
    @Unique private BlazePrograms overhaul$programs;

    @Redirect(method="getOrCreate",at=@At(value="INVOKE",target="Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),require=1)
    private static Object overhaul$localStage(Map<?,?> cache,Object key){return BlazePrograms.current()==null?cache.get(key):null;}

    /** Every constructor: mods call the location one directly, past the string one that delegates to it. */
    @Inject(method="<init>*",at=@At("RETURN"),require=1)
    private void overhaul$resources(CallbackInfo callback) {
        if(overhaul$programs!=null)return;
        ShaderInstance self=(ShaderInstance)(Object)this;var stage=((BlazeStageAccess)self.getVertexProgram()).overhaul$stage();
        if(stage!=null){overhaul$programs=stage.owner();overhaul$programs.registerSnapshot(self.getId(),()->overhaul$programs.snapshot(self.getId(),uniformMap));}
    }

    @Override public BlazeShaderSnapshot overhaul$shaderSnapshot(BlazeProgramCompiler compiler) {
        ShaderInstance self = (ShaderInstance) (Object) this;
        if(overhaul$programs==null||overhaul$programs.compiler()!=compiler)throw new IllegalStateException("Shader belongs to another program registry");
        return overhaul$programs.snapshot(self.getId(),uniformMap);
    }

    @Override public BlazeProgramCompiler.Program overhaul$sectionProgram(){return overhaul$programs==null?null:overhaul$programs.sectionProgram(((ShaderInstance)(Object)this).getId());}
    @Override public BlazeProgramCompiler.Program overhaul$compactSectionProgram(){return overhaul$programs==null?null:overhaul$programs.compactSectionProgram(((ShaderInstance)(Object)this).getId());}
    @Override public BlazeShaderSnapshot overhaul$sectionSnapshot(){return overhaul$programs.sectionSnapshot(((ShaderInstance)(Object)this).getId(),uniformMap);}

    @Inject(method="apply",at=@At("HEAD"),require=1)
    private void overhaul$apply(CallbackInfo callback){if(overhaul$programs!=null)overhaul$programs.use(((ShaderInstance)(Object)this).getId());}
}
