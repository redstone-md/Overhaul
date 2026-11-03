package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MainTarget has a separate allocation method; route it through the same owned attachment factory. */
@Mixin(MainTarget.class)
public abstract class MainTargetBlazeMixin {
    @Inject(method="createFrameBuffer(II)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
    private void overhaul$mainTarget(int width,int height,CallbackInfo callback){
        if(BlazeFrontend.current()!=null){((RenderTarget)(Object)this).createBuffers(width,height,false);callback.cancel();}
    }
}
