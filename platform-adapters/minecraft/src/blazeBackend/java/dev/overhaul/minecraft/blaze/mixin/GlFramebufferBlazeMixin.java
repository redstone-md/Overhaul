package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.overhaul.minecraft.blaze.BlazeFramebufferBlit;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeGlBoundary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Public framebuffer operations resolve only real owned attachments. */
@Mixin(value=GlStateManager.class, remap=false)
public abstract class GlFramebufferBlazeMixin {
    @Inject(method="_glBindFramebuffer",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$bind(int target,int id,CallbackInfo c){var owner=BlazeFrontend.current();if(owner!=null){owner.bindFramebuffer(target,id);c.cancel();}}
    @Inject(method="getBoundFramebuffer",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$bound(CallbackInfoReturnable<Integer> c){var owner=BlazeFrontend.current();if(owner!=null)c.setReturnValue(owner.drawState().integer(0x8CA6));}
    @Inject(method="_glDeleteFramebuffers",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$delete(int id,CallbackInfo c){var owner=BlazeFrontend.current();if(owner!=null){owner.unregisterTarget(id);c.cancel();}}
    @Inject(method="glCheckFramebufferStatus",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$status(int target,CallbackInfoReturnable<Integer> c){var owner=BlazeFrontend.current();if(owner!=null){if(target!=0x8D40&&target!=0x8CA8&&target!=0x8CA9)throw new IllegalArgumentException("Unknown framebuffer query");owner.currentTarget();owner.readTarget();c.setReturnValue(0x8CD5);}}
    @Inject(method="_glBlitFrameBuffer",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$copy(int sx0,int sy0,int sx1,int sy1,int dx0,int dy0,int dx1,int dy1,int mask,int filter,CallbackInfo c){
        var owner=BlazeFrontend.current();if(owner!=null){BlazeFramebufferBlit.copy(owner,sx0,sy0,sx1,sy1,dx0,dy0,dx1,dy1,mask,filter);c.cancel();}
    }
    @Inject(method="_drawElements",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$rawDraw(CallbackInfo c){
        if(BlazeFrontend.current()==null)return;
        BlazeGlBoundary.reportOnce("_drawElements","A mod issued a raw VAO draw; it has no native Vulkan path and draws nothing");c.cancel();
    }
}
