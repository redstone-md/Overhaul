package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeRenderer;
import dev.overhaul.minecraft.blaze.BlazeTarget;
import dev.overhaul.minecraft.blaze.BlazeUpscale;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RenderTarget owns its framebuffer; texture aliases borrow those exact native views. */
@Mixin(RenderTarget.class)
public abstract class RenderTargetBlazeMixin {
    @Shadow public int width,height,viewWidth,viewHeight,frameBufferId,filterMode;
    @Shadow protected int colorTextureId,depthBufferId;
    @Shadow @Final public boolean useDepth;
    @Unique private BlazeTarget overhaul$target;
    @Unique private BlazeRenderer overhaul$owner;
    @Unique private boolean overhaul$owned;

    @Inject(method="<init>(Z)V",at=@At("RETURN"),require=1)
    private void overhaul$ownership(boolean useDepth,CallbackInfo c){overhaul$owned=BlazeFrontend.current()!=null;}

    @Inject(method="createBuffers(IIZ)V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$create(int width,int height,boolean checkError,CallbackInfo c){
        BlazeRenderer owner=BlazeFrontend.current();if(owner==null)return;
        if(!overhaul$owned)throw new IllegalStateException("OpenGL framebuffer cannot migrate between devices");
        if(overhaul$target!=null)throw new IllegalStateException("Framebuffer already owns native storage");
        BlazeTarget next=new BlazeTarget(owner.context(),owner.allocator(),owner.commands(),width,height,useDepth,((RenderTarget)(Object)this).isStencilEnabled());
        int color=-1,depth=-1,id=-1;
        try{
            color=owner.textures().borrow(next.colorView());
            if(useDepth)depth=owner.textures().borrow(next.depthView());
            id=owner.registerTarget(next);
        }catch(RuntimeException|Error failure){
            if(id>=0)try{owner.unregisterTarget(id);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            if(depth>=0)try{owner.textures().delete(depth);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            if(color>=0)try{owner.textures().delete(color);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            try{next.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
        overhaul$target=next;overhaul$owner=owner;
        this.width=viewWidth=width;this.height=viewHeight=height;frameBufferId=id;colorTextureId=color;depthBufferId=depth;
        RenderTarget self=(RenderTarget)(Object)this;
        // Vanilla createBuffers forces nearest on each new allocation, including resize.
        filterMode=-1;self.setFilterMode(9728);self.clear(checkError);self.unbindRead();c.cancel();
    }

    /** Code that binds the main target back without its viewport still expects one of the main target's size. */
    @Inject(method="bindWrite(Z)V",at=@At("RETURN"),require=1)
    private void overhaul$bound(boolean setViewport,CallbackInfo c){if(!setViewport)BlazeUpscale.get().bound((RenderTarget)(Object)this);}

    @Inject(method="destroyBuffers()V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$destroy(CallbackInfo c){
        if(!overhaul$owned){if(BlazeFrontend.current()!=null)throw new IllegalStateException("OpenGL framebuffer cannot migrate between devices");return;}
        if(overhaul$target==null){
            var owner=BlazeFrontend.current();
            if(owner!=null&&!owner.isClosed()){owner.textures().bind(0);owner.bindFramebuffer(0x8D40,0);}
            frameBufferId=colorTextureId=depthBufferId=-1;c.cancel();return;
        }
        BlazeTarget target=overhaul$target;BlazeRenderer owner=overhaul$owner;
        overhaul$target=null;overhaul$owner=null;
        Throwable failure=null;
        if(!owner.isClosed()){
            try{owner.textures().bind(0);owner.bindFramebuffer(0x8D40,0);}catch(RuntimeException|Error problem){failure=problem;}
            try{owner.textures().delete(colorTextureId);}catch(RuntimeException|Error problem){failure=overhaul$failure(failure,problem);}
            if(depthBufferId>=0)try{owner.textures().delete(depthBufferId);}catch(RuntimeException|Error problem){failure=overhaul$failure(failure,problem);}
            try{owner.unregisterTarget(frameBufferId);}catch(RuntimeException|Error problem){failure=overhaul$failure(failure,problem);}
        }
        try{target.close();}catch(RuntimeException|Error problem){failure=overhaul$failure(failure,problem);}
        frameBufferId=colorTextureId=depthBufferId=-1;c.cancel();
        if(failure instanceof RuntimeException)throw(RuntimeException)failure;if(failure instanceof Error)throw(Error)failure;
    }
    @Unique private static Throwable overhaul$failure(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
}
