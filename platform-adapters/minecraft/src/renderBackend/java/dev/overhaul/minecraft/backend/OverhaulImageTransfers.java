package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.ImageCommand;
import dev.overhaul.vulkan.ImageRegion;
import org.joml.Vector4fc;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

/** RenderPearl region/stride semantics translated into ordered native image commands. */
final class OverhaulImageTransfers {
    private final OverhaulDeviceBackend owner;
    OverhaulImageTransfers(OverhaulDeviceBackend owner) { this.owner=owner; }

    private void enqueue(Runnable callback,Consumer<OverhaulImageOperation> builder,Object... resources) {
        var operation=new OverhaulImageOperation(owner,callback,resources);
        try { builder.accept(operation); }
        catch (RuntimeException|Error failure) {
            try { operation.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        // Enqueue takes ownership, including rejection and submission failure.
        owner.queue().enqueue(operation);
    }
    private GpuImage image(GpuTexture texture) { return owner.image(texture); }

    void clearColor(GpuTexture texture,Vector4fc color) {
        enqueue(null,operation->{
            GpuImage image=image(texture);
            for (int layer=0;layer<image.layers();layer++) for (int mip=0;mip<image.mipLevels();mip++) {
                operation.add(ImageCommand.clearColor(owner.context(),image,mip,layer,color.x(),color.y(),color.z(),color.w()));
            }
        },texture);
    }
    void clearDepth(GpuTexture texture,double depth) {
        enqueue(null,operation->{
            GpuImage image=image(texture);
            for (int layer=0;layer<image.layers();layer++) for (int mip=0;mip<image.mipLevels();mip++) {
                operation.add(ImageCommand.clearDepth(owner.context(),image,mip,layer,(float)depth,0));
            }
        },texture);
    }
    void upload(GpuTexture texture,ByteBuffer source,int mip,int layer,int x,int y,int width,int height) {
        enqueue(null,operation->operation.add(ImageCommand.upload(owner.context(),image(texture),
                new ImageRegion(mip,layer,x,y,width,height),source,0)),texture);
    }
    void copyBufferToTexture(GpuBufferSlice source,int sourceX,int sourceY,int sourceWidth,int sourceHeight,
            GpuTexture texture,int destX,int destY,int width,int height,int mip,int layer) {
        if (width<1||height<1||sourceWidth<1||sourceHeight<1||sourceX<0||sourceY<0
                ||sourceX>sourceWidth-width||sourceY>sourceHeight-height) throw new IllegalArgumentException("Source pixel rectangle is invalid");
        int bytesPerPixel=texture.getFormat().blockSize();
        int rowPitch=Math.multiplyExact(sourceWidth,bytesPerPixel);
        long required=Math.multiplyExact((long)sourceHeight,rowPitch);
        if (required>source.length()) throw new IllegalArgumentException("Source dimensions exceed buffer slice");
        long offset=Math.addExact(source.offset(),Math.addExact(Math.multiplyExact((long)sourceY,rowPitch),Math.multiplyExact((long)sourceX,bytesPerPixel)));
        long touched=Math.addExact(Math.multiplyExact((long)height-1,rowPitch),Math.multiplyExact((long)width,bytesPerPixel));
        enqueue(null,operation->{
            operation.add(ImageCommand.copyBufferToImage(owner.context(),owner.buffer(source.buffer()),offset,rowPitch,
                    image(texture),new ImageRegion(mip,layer,destX,destY,width,height)));
            operation.touches(source.buffer(),offset,touched);
        },source.buffer(),texture);
    }
    void copyTextureToBuffer(GpuTexture texture,GpuBuffer destination,long offset,Runnable callback,int mip,int x,int y,int width,int height) {
        Objects.requireNonNull(callback,"callback");
        if (width<1||height<1) throw new IllegalArgumentException("Readback rectangle is empty");
        long bytesPerLayer=Math.multiplyExact(Math.multiplyExact((long)width,height),texture.getFormat().blockSize());
        long bytes=Math.multiplyExact(bytesPerLayer,texture.getDepthOrLayers());
        if (offset<0||offset>destination.size()-bytes) throw new IllegalArgumentException("Readback exceeds destination buffer");
        enqueue(callback,operation->{
            for (int layer=0;layer<texture.getDepthOrLayers();layer++) {
                operation.add(ImageCommand.copyImageToBuffer(owner.context(),image(texture),new ImageRegion(mip,layer,x,y,width,height),
                        owner.buffer(destination),Math.addExact(offset,Math.multiplyExact(bytesPerLayer,layer)),0));
            }
            operation.touches(destination,offset,bytes);
        },texture,destination);
    }
    void copyTextureToTexture(GpuTexture source,GpuTexture destination,int mip,int destX,int destY,int sourceX,int sourceY,int width,int height) {
        if (source.getFormat()!=destination.getFormat()||source.getDepthOrLayers()!=destination.getDepthOrLayers()) {
            throw new IllegalArgumentException("Image copy formats and layer counts must match");
        }
        enqueue(null,operation->{
            for (int layer=0;layer<source.getDepthOrLayers();layer++) {
                operation.add(ImageCommand.copy(owner.context(),image(source),new ImageRegion(mip,layer,sourceX,sourceY,width,height),
                        image(destination),new ImageRegion(mip,layer,destX,destY,width,height)));
            }
        },source,destination);
    }
}
