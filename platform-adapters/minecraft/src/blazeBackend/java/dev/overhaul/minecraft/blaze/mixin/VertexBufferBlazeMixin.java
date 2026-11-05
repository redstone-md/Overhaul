package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeMesh;
import dev.overhaul.minecraft.blaze.BlazeVertexBufferAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One interception covers immediate BufferUploader draws and persistent terrain/model buffers. */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferBlazeMixin implements BlazeVertexBufferAccess {
    @Shadow private int vertexBufferId,indexBufferId,arrayObjectId,indexCount;
    @Shadow private VertexFormat format;
    @Unique private BlazeMesh overhaul$mesh;
    @Unique private boolean overhaul$owned,overhaul$stream;

    @Redirect(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I",remap=false),require=2,allow=2)
    private int overhaul$bufferId(){var owner=BlazeFrontend.current();if(owner==null)return GlStateManager._glGenBuffers();overhaul$owned=true;return owner.nextHandle();}
    @Redirect(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I",remap=false),require=1,allow=1)
    private int overhaul$arrayId(){var owner=BlazeFrontend.current();if(owner==null)return GlStateManager._glGenVertexArrays();overhaul$owned=true;return owner.nextHandle();}

    @Override public boolean overhaul$owned(){return overhaul$owned;}
    @Override public void overhaul$streamUploads(){overhaul$stream=true;}
    @Override public BlazeMesh overhaul$uploadMesh(){
        if(!overhaul$owned)throw new IllegalStateException("OpenGL vertex buffer cannot migrate between devices");
        var owner=BlazeFrontend.require();
        if(overhaul$mesh==null)overhaul$mesh=owner.newMesh(overhaul$stream);
        return overhaul$mesh;
    }
    @Override public BlazeMesh overhaul$existingMesh(){
        if(!overhaul$owned)throw new IllegalStateException("OpenGL vertex buffer has no owned upload");
        BlazeFrontend.require();
        if(overhaul$mesh==null)throw new IllegalStateException("Sorted indices before native vertex upload");
        return overhaul$mesh;
    }
    @Override public BlazeMesh overhaul$meshOrNull(){return overhaul$mesh;}
    @Inject(method="bind()V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$bind(CallbackInfo callback){if(overhaul$owned)callback.cancel();else if(BlazeFrontend.current()!=null)throw new IllegalStateException("OpenGL vertex buffer cannot bind on the owned device");}
    @Inject(method="unbind()V",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$unbind(CallbackInfo callback){if(BlazeFrontend.current()!=null)callback.cancel();}
    @Inject(method="draw()V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$draw(CallbackInfo callback){var owner=BlazeFrontend.current();if(overhaul$owned){if(overhaul$mesh!=null)BlazeFrontend.require().draw(overhaul$mesh);callback.cancel();}else if(owner!=null)throw new IllegalStateException("OpenGL vertex buffer cannot draw on the owned device");}
    @Inject(method="close()V",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void overhaul$close(CallbackInfo callback){
        if(!overhaul$owned)return;
        if(overhaul$mesh!=null){overhaul$mesh.close();overhaul$mesh=null;}
        vertexBufferId=indexBufferId=arrayObjectId=-1;indexCount=0;format=null;callback.cancel();
    }
}
