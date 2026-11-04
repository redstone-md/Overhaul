package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.shaders.Uniform;
import dev.overhaul.minecraft.blaze.BlazePrograms;
import dev.overhaul.minecraft.blaze.BlazeShaderSnapshot;
import dev.overhaul.minecraft.blaze.BlazeStageAccess;
import java.util.Map;
import net.minecraft.client.renderer.EffectInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EffectInstance.class)
public abstract class EffectInstanceBlazeMixin {
    @Shadow @Final private Map<String,Uniform> uniformMap;
    @Unique private BlazePrograms overhaul$programs;
    @Redirect(method="getOrCreate",at=@At(value="INVOKE",target="Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"),require=1)
    private static Object overhaul$localStage(Map<?,?> cache,Object key){return BlazePrograms.current()==null?cache.get(key):null;}
    @Inject(method="<init>",at=@At("RETURN"),require=1)
    private void overhaul$created(CallbackInfo callback){
        var self=(EffectInstance)(Object)this;var stage=((BlazeStageAccess)self.getVertexProgram()).overhaul$stage();
        if(stage!=null){overhaul$programs=stage.owner();overhaul$programs.registerSnapshot(self.getId(),this::overhaul$snapshot);}
    }
    @Unique private BlazeShaderSnapshot overhaul$snapshot(){return overhaul$programs.snapshot(((EffectInstance)(Object)this).getId(),uniformMap);}
    @Inject(method="apply",at=@At("HEAD"),require=1)
    private void overhaul$apply(CallbackInfo callback){
        if(overhaul$programs!=null)overhaul$programs.use(((EffectInstance)(Object)this).getId());
    }
}
