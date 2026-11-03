package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;

/** Own command execution over fence-backed Vulkan submissions; no vanilla backend delegation. */
public final class OverhaulCommandEncoder implements CommandEncoderBackend,AutoCloseable {
    private final OverhaulDeviceBackend owner;
    private final OverhaulTransientMemory transientMemory;
    private final OverhaulImageTransfers images;
    private OverhaulRenderPass activePass;
    private boolean closed;

    public OverhaulCommandEncoder(OverhaulDeviceBackend owner) {
        this.owner=owner;
        transientMemory=new OverhaulTransientMemory(owner);
        images=new OverhaulImageTransfers(owner);
    }

    public OverhaulDeviceBackend owner() { return owner; }
    @Override public TransientMemory transientMemory() { requireOpen(); return transientMemory; }

    @Override public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        requireNoPass();
        activePass=new OverhaulRenderPass(owner,descriptor);
        return activePass;
    }

    @Override public void submitRenderPass() {
        requireOpen();
        if (activePass==null) throw new IllegalStateException("No render pass is open");
        OverhaulRenderPass pass=activePass; activePass=null;
        owner.queue().enqueue(new PassOperation(pass));
        owner.rendered(pass.drawCount());
    }

    @Override public void submit() {
        requireNoPass();
        transientMemory.finishFrame(owner.queue().flush());
        owner.queue().poll();
    }

    @Override public void writeToBuffer(GpuBufferSlice destination,ByteBuffer source) {
        requireNoPass();
        if (source.remaining()>destination.length()) throw new IllegalArgumentException("Source exceeds destination slice");
        if (source.remaining()==0) return;
        var staging=transientMemory.uploadStaging(java.util.List.of(source),4,GpuBuffer.USAGE_COPY_SRC,source.remaining(),1);
        owner.queue().enqueue(new BufferCopyOperation(owner,staging,destination));
    }

    @Override public void copyToBuffer(GpuBufferSlice source,GpuBufferSlice destination) {
        requireNoPass();
        if (source.length()>destination.length()) throw new IllegalArgumentException("Destination slice is too short");
        if (source.length()==0) return;
        owner.queue().enqueue(new BufferCopyOperation(owner,source,destination));
    }

    @Override public void clearColorTexture(GpuTexture texture,Vector4fc color) {
        requireNoPass(); images.clearColor(texture,color);
    }
    @Override public void clearDepthTexture(GpuTexture texture,double depth) {
        requireNoPass(); images.clearDepth(texture,depth);
    }
    @Override public void clearColorAndDepthTextures(GpuTexture colorTexture,Vector4fc color,GpuTexture depthTexture,double depth) {
        requireNoPass(); images.clearColor(colorTexture,color); images.clearDepth(depthTexture,depth);
    }
    @Override public void clearColorAndDepthTextures(GpuTexture colorTexture,Vector4fc color,GpuTexture depthTexture,double depth,
            int x,int y,int width,int height,int mip) {
        requireNoPass();
        try (GpuTextureView colorView=owner.createTextureView(colorTexture,mip,1);
             GpuTextureView depthView=owner.createTextureView(depthTexture,mip,1)) {
            var descriptor=RenderPassDescriptor.builder(()->"overhaul region clear")
                    .withColorAttachment(colorView,java.util.Optional.of(new org.joml.Vector4f(color)))
                    .withDepthAttachment(depthView,java.util.OptionalDouble.of(depth))
                    .withRenderArea(new RenderPass.RenderArea(x,y,width,height)).build();
            createRenderPass(descriptor); submitRenderPass();
        }
    }

    @Override public void writeToTexture(GpuTexture texture,ByteBuffer source,int mip,int layer,int x,int y,int width,int height) {
        requireNoPass(); images.upload(texture,source,mip,layer,x,y,width,height);
    }
    @Override public void copyBufferToTexture(GpuBufferSlice source,int sourceX,int sourceY,int sourceWidth,int sourceHeight,
            GpuTexture texture,int destX,int destY,int width,int height,int mip,int layer) {
        requireNoPass(); images.copyBufferToTexture(source,sourceX,sourceY,sourceWidth,sourceHeight,texture,destX,destY,width,height,mip,layer);
    }
    @Override public void copyTextureToBuffer(GpuTexture texture,GpuBuffer destination,long offset,Runnable callback,int mip) {
        requireNoPass(); images.copyTextureToBuffer(texture,destination,offset,callback,mip,0,0,texture.getWidth(mip),texture.getHeight(mip));
    }
    @Override public void copyTextureToBuffer(GpuTexture texture,GpuBuffer destination,long offset,Runnable callback,int mip,int x,int y,int width,int height) {
        requireNoPass(); images.copyTextureToBuffer(texture,destination,offset,callback,mip,x,y,width,height);
    }
    @Override public void copyTextureToTexture(GpuTexture source,GpuTexture destination,int mip,int destX,int destY,int sourceX,int sourceY,int width,int height) {
        requireNoPass(); images.copyTextureToTexture(source,destination,mip,destX,destY,sourceX,sourceY,width,height);
    }

    @Override public GpuFence createFence() {
        requireNoPass();
        return SubmissionGpuFence.capture(owner);
    }
    @Override public void writeTimestamp(GpuQueryPool pool,int index) {
        requireNoPass();
        if (!(pool instanceof OverhaulQueryPool query) || query.owner()!=owner) throw new IllegalArgumentException("Foreign query pool");
        owner.queue().enqueue(new TimestampOperation(owner,query,index));
    }

    public void requireNoPass() {
        requireOpen();
        if (activePass!=null) throw new IllegalStateException("Close the active render pass before other commands");
    }
    private void requireOpen() {
        owner.requireOpen();
        if (closed) throw new IllegalStateException("Command encoder is closed");
    }
    @Override public void close() {
        if (closed) return;
        requireOpen();
        if (activePass!=null) { activePass.close(); activePass=null; }
        owner.queue().drain(); transientMemory.close(); closed=true;
    }
}
