package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeSpriteInterpolation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blends animation frames on raw memory; the vanilla per-pixel loop stays as the fallback. */
@Mixin(targets="net.minecraft.client.renderer.texture.SpriteContents$InterpolationData")
abstract class SpriteInterpolationBlazeMixin {
    @Inject(method="uploadInterpolatedFrame",at=@At("HEAD"),cancellable=true)
    private void overhaul$blend(int x,int y,@Coerce Object ticker,CallbackInfo callback){
        if(BlazeSpriteInterpolation.upload(this,x,y,ticker))callback.cancel();
    }
}
