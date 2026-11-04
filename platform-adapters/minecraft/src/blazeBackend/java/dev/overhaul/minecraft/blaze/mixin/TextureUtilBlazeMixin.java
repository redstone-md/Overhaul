package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureUtil.class)
public abstract class TextureUtilBlazeMixin {
    @Inject(target=@Desc(value="prepareImage", args={NativeImage.InternalGlFormat.class,int.class,int.class,int.class,int.class}),
            remap=false, at=@At("HEAD"), cancellable=true)
    private static void overhaul$allocate(NativeImage.InternalGlFormat internal,int id,int maximumMip,int width,int height,CallbackInfo callback){
        var renderer=BlazeFrontend.current();if(renderer!=null){renderer.textures().prepare(internal.glFormat(),id,maximumMip,width,height);callback.cancel();}
    }
}
