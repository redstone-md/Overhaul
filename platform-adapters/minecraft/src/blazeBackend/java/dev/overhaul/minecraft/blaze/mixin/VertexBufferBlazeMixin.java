package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.BlazeMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One interception covers immediate BufferUploader draws and persistent terrain/model buffers. */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferBlazeMixin {
    @Shadow private int vertexBufferId,indexBufferId,arrayObjectId,indexCount;
    @Shadow private VertexFormat format;
    @Shadow private VertexFormat.Mode mode;
    @Shadow private VertexFormat.IndexType indexType;
    @Unique private BlazeMesh overhaul$mesh;
    @Unique private boolean overhaul$owned;

    @Redirect(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I"),require=2,allow=2)
    private int overhaul$bufferId(){var owner=BlazeFrontend.current();if(owner==null)return GlStateManager._glGenBuffers();overhaul$owned=true;return owner.nextHandle();}
    @Redirect(method="<init>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I"),require=1,allow=1)
    private int overhaul$arrayId(){var owner=BlazeFrontend.current();if(owner==null)return GlStateManager._glGenVertexArrays();overhaul$owned=true;return owner.nextHandle();}

    @Inject(method="upload(Lcom/mojang/blaze3d/vertex/MeshData;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$upload(MeshData source,CallbackInfo callback){
        var owner=BlazeFrontend.current();
        if(!overhaul$owned){if(owner!=null){source.close();throw new IllegalStateException("OpenGL vertex buffer cannot migrate between devices");}return;}
        if(owner==null){source.close();throw new IllegalStateException("Owned vertex buffer has no active device");}
        if(vertexBufferId<0){source.close();callback.cancel();return;}
        var state=source.drawState();
        if(overhaul$mesh==null)overhaul$mesh=owner.newMesh();
        overhaul$mesh.upload(source);
        format=state.format();mode=state.mode();indexType=state.indexType();indexCount=state.indexCount();callback.cancel();
    }

    @Inject(method="uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$indices(ByteBufferBuilder.Result source,CallbackInfo callback){
        if(!overhaul$owned){if(BlazeFrontend.current()!=null){source.close();throw new IllegalStateException("OpenGL vertex buffer cannot migrate between devices");}return;}
        if(BlazeFrontend.current()==null){source.close();throw new IllegalStateException("Owned vertex buffer has no active device");}
        if(overhaul$mesh==null){source.close();throw new IllegalStateException("Sorted indices before native vertex upload");}
        overhaul$mesh.uploadIndices(source);callback.cancel();
    }
    @Inject(method="bind()V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$bind(CallbackInfo callback){if(overhaul$owned)callback.cancel();else if(BlazeFrontend.current()!=null)throw new IllegalStateException("OpenGL vertex buffer cannot bind on the owned device");}
    @Inject(method="unbind()V",at=@At("HEAD"),cancellable=true,require=1)
    private static void overhaul$unbind(CallbackInfo callback){if(BlazeFrontend.current()!=null)callback.cancel();}
    @Inject(method="draw()V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$draw(CallbackInfo callback){var owner=BlazeFrontend.current();if(overhaul$owned){if(overhaul$mesh!=null)BlazeFrontend.require().draw(overhaul$mesh);callback.cancel();}else if(owner!=null)throw new IllegalStateException("OpenGL vertex buffer cannot draw on the owned device");}
    @Inject(method="close()V",at=@At("HEAD"),cancellable=true,require=1)
    private void overhaul$close(CallbackInfo callback){
        if(!overhaul$owned)return;
        if(overhaul$mesh!=null){overhaul$mesh.close();overhaul$mesh=null;}
        vertexBufferId=indexBufferId=arrayObjectId=-1;indexCount=0;format=null;callback.cancel();
    }
}
