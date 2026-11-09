package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.overhaul.minecraft.blaze.BlazeDrawState;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game still determines state changes; the selected device stores their exact native meaning.
 * The original bodies keep running because mods read GlStateManager's cache: ImmediatelyFast re-applies it at batch flush.
 */
@Mixin(value=GlStateManager.class, remap=false)
public abstract class GlStateBlazeMixin {
    @Unique private static BlazeDrawState overhaul$state(){var renderer=BlazeFrontend.current();return renderer==null?null:renderer.drawState();}
    @Inject(method="_enableDepthTest",at=@At("HEAD"),require=1)
    private static void overhaul$depthOn(CallbackInfo c){var s=overhaul$state();if(s!=null){s.depth(true);}}
    @Inject(method="_disableDepthTest",at=@At("HEAD"),require=1)
    private static void overhaul$depthOff(CallbackInfo c){var s=overhaul$state();if(s!=null){s.depth(false);}}
    @Inject(method="_depthFunc",at=@At("HEAD"),require=1)
    private static void overhaul$depthFunc(int value,CallbackInfo c){var s=overhaul$state();if(s!=null){s.depthFunction(value);}}
    @Inject(method="_depthMask",at=@At("HEAD"),require=1)
    private static void overhaul$depthMask(boolean value,CallbackInfo c){var s=overhaul$state();if(s!=null){s.depthWrite(value);}}
    @Inject(method="_enableBlend",at=@At("HEAD"),require=1)
    private static void overhaul$blendOn(CallbackInfo c){var s=overhaul$state();if(s!=null){s.blend(true);}}
    @Inject(method="_disableBlend",at=@At("HEAD"),require=1)
    private static void overhaul$blendOff(CallbackInfo c){var s=overhaul$state();if(s!=null){s.blend(false);}}
    @Inject(method="_blendFunc",at=@At("HEAD"),require=1)
    private static void overhaul$blend(int source,int destination,CallbackInfo c){var s=overhaul$state();if(s!=null){s.blendFunctions(source,destination,source,destination);}}
    @Inject(method="_blendFuncSeparate",at=@At("HEAD"),require=1)
    private static void overhaul$blendSeparate(int sr,int dr,int sa,int da,CallbackInfo c){var s=overhaul$state();if(s!=null){s.blendFunctions(sr,dr,sa,da);}}
    @Inject(method="_blendEquation",at=@At("HEAD"),require=1)
    private static void overhaul$blendEquation(int value,CallbackInfo c){var s=overhaul$state();if(s!=null){s.blendEquation(value);}}
    @Inject(method="_enableCull",at=@At("HEAD"),require=1)
    private static void overhaul$cullOn(CallbackInfo c){var s=overhaul$state();if(s!=null){s.cull(true);}}
    @Inject(method="_disableCull",at=@At("HEAD"),require=1)
    private static void overhaul$cullOff(CallbackInfo c){var s=overhaul$state();if(s!=null){s.cull(false);}}
    @Inject(method="_enableScissorTest",at=@At("HEAD"),require=1)
    private static void overhaul$scissorOn(CallbackInfo c){var s=overhaul$state();if(s!=null){s.scissor(true);}}
    @Inject(method="_disableScissorTest",at=@At("HEAD"),require=1)
    private static void overhaul$scissorOff(CallbackInfo c){var s=overhaul$state();if(s!=null){s.scissor(false);}}
    @Inject(method="_scissorBox",at=@At("HEAD"),require=1)
    private static void overhaul$scissor(int x,int y,int width,int height,CallbackInfo c){var s=overhaul$state();if(s!=null){s.scissorBox(x,y,width,height);}}
    @Inject(method="_viewport",at=@At("HEAD"),require=1)
    private static void overhaul$viewport(int x,int y,int width,int height,CallbackInfo c){var s=overhaul$state();if(s!=null){s.viewport(x,y,width,height);}}
    @Inject(method="_colorMask",at=@At("HEAD"),require=1)
    private static void overhaul$mask(boolean r,boolean g,boolean b,boolean a,CallbackInfo c){var s=overhaul$state();if(s!=null){s.colorMask(r,g,b,a);}}
    @Inject(method="_enableColorLogicOp",at=@At("HEAD"),require=1)
    private static void overhaul$logicOn(CallbackInfo c){var s=overhaul$state();if(s!=null){s.logic(true);}}
    @Inject(method="_disableColorLogicOp",at=@At("HEAD"),require=1)
    private static void overhaul$logicOff(CallbackInfo c){var s=overhaul$state();if(s!=null){s.logic(false);}}
    @Inject(method="_logicOp",at=@At("HEAD"),require=1)
    private static void overhaul$logic(int value,CallbackInfo c){var s=overhaul$state();if(s!=null){s.logicOperation(value);}}
    @Inject(method="_polygonMode",at=@At("HEAD"),require=1)
    private static void overhaul$polygon(int face,int mode,CallbackInfo c){var s=overhaul$state();if(s!=null){s.polygonMode(face,mode);}}
    @Inject(method="_enablePolygonOffset",at=@At("HEAD"),require=1)
    private static void overhaul$offsetOn(CallbackInfo c){var s=overhaul$state();if(s!=null){s.polygonOffset(true);}}
    @Inject(method="_disablePolygonOffset",at=@At("HEAD"),require=1)
    private static void overhaul$offsetOff(CallbackInfo c){var s=overhaul$state();if(s!=null){s.polygonOffset(false);}}
    @Inject(method="_polygonOffset",at=@At("HEAD"),require=1)
    private static void overhaul$offset(float factor,float units,CallbackInfo c){var s=overhaul$state();if(s!=null){s.polygonOffset(factor,units);}}
    @Inject(method="_clearColor",at=@At("HEAD"),require=1)
    private static void overhaul$clearColor(float r,float g,float b,float a,CallbackInfo c){var s=overhaul$state();if(s!=null){s.clearColor(r,g,b,a);}}
    @Inject(method="_clearDepth",at=@At("HEAD"),require=1)
    private static void overhaul$clearDepth(double value,CallbackInfo c){var s=overhaul$state();if(s!=null){s.clearDepth(value);}}
    @Inject(method="_clear",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$clear(int mask,boolean checkError,CallbackInfo c){var owner=BlazeFrontend.current();if(owner!=null){owner.clear(mask);c.cancel();}}
    // TACZ scopes mask their lens with the stencil every frame in first person.
    @Inject(method="_stencilFunc",at=@At("HEAD"),require=1)
    private static void overhaul$stencilFunc(int func,int ref,int mask,CallbackInfo c){var s=overhaul$state();if(s!=null){s.stencil().function(func,ref,mask);}}
    @Inject(method="_stencilMask",at=@At("HEAD"),require=1)
    private static void overhaul$stencilMask(int mask,CallbackInfo c){var s=overhaul$state();if(s!=null){s.stencil().mask(mask);}}
    @Inject(method="_stencilOp",at=@At("HEAD"),require=1)
    private static void overhaul$stencilOp(int fail,int depthFail,int pass,CallbackInfo c){var s=overhaul$state();if(s!=null){s.stencil().operation(fail,depthFail,pass);}}
    @Inject(method="_clearStencil",at=@At("HEAD"),require=1)
    private static void overhaul$clearStencil(int value,CallbackInfo c){var s=overhaul$state();if(s!=null){s.stencil().clear(value);}}
}
