package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.overhaul.minecraft.blaze.BlazeCapabilities;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every selected-device query is resolved locally; an unsupported query cannot reach a missing GL context. */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerBlazeCapabilitiesMixin {
    @Inject(method="_getInteger",at=@At("HEAD"),cancellable=true)
    private static void overhaul$integer(int name,CallbackInfoReturnable<Integer> callback){
        var renderer=BlazeFrontend.current();if(renderer==null)return;
        Integer value=renderer.textures().integer(name);if(value==null)value=renderer.drawState().integer(name);
        callback.setReturnValue(value==null?BlazeCapabilities.integer(renderer.context(),name,renderer.textures().textureUnits()):value);
    }
    @Inject(method="_getString",at=@At("HEAD"),cancellable=true)
    private static void overhaul$string(int name,CallbackInfoReturnable<String> callback){
        var renderer=BlazeFrontend.current();if(renderer!=null)callback.setReturnValue(BlazeCapabilities.string(renderer.context(),name));
    }
    @Inject(method="_getError",at=@At("HEAD"),cancellable=true)
    private static void overhaul$error(CallbackInfoReturnable<Integer> callback){if(BlazeFrontend.current()!=null)callback.setReturnValue(0);}
}
