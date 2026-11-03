package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.ImageRegion;
import org.joml.Vector4fc;
import java.nio.ByteBuffer;

/** RenderPearl region/stride semantics translated to the engine's validated image transfer API. */
final class OverhaulImageTransfers {
    private final OverhaulDeviceBackend owner;
    OverhaulImageTransfers(OverhaulDeviceBackend owner) { this.owner=owner; }

    private GpuImage image(GpuTexture texture) {
        if (!(texture instanceof OverhaulTexture own) || own.owner()!=owner) throw new IllegalArgumentException("Foreign texture");
        return own.image();
    }

    void clearColor(GpuTexture texture,Vector4fc color) {
        try (BackendPins pins=new BackendPins(owner,texture)) {
            GpuImage image=image(texture);
            for (int layer=0;layer<image.layers();layer++) for (int mip=0;mip<image.mipLevels();mip++) {
                owner.commands().clearColor(image,mip,layer,color.x(),color.y(),color.z(),color.w());
            }
        }
    }

    void clearDepth(GpuTexture texture,double depth) {
        try (BackendPins pins=new BackendPins(owner,texture)) {
            GpuImage image=image(texture);
            for (int layer=0;layer<image.layers();layer++) for (int mip=0;mip<image.mipLevels();mip++) {
                owner.commands().clearDepth(image,mip,layer,(float)depth);
            }
        }
    }

    void upload(GpuTexture texture,ByteBuffer source,int mip,int layer,int x,int y,int width,int height) {
        try (BackendPins pins=new BackendPins(owner,texture)) {
            owner.commands().upload(image(texture),new ImageRegion(mip,layer,x,y,width,height),source,0);
        }
    }

    void copyBufferToTexture(GpuBufferSlice source,int sourceX,int sourceY,int sourceWidth,int sourceHeight,
            GpuTexture texture,int destX,int destY,int width,int height,int mip,int layer) {
        if (sourceWidth<1 || sourceHeight<1 || sourceX<0 || sourceY<0
                || sourceX>sourceWidth-width || sourceY>sourceHeight-height) throw new IllegalArgumentException("Source pixel rectangle is invalid");
        int bytesPerPixel=texture.getFormat().blockSize();
        int rowPitch=Math.multiplyExact(sourceWidth,bytesPerPixel);
        long required=Math.multiplyExact((long)sourceHeight,rowPitch);
        if (required>source.length()) throw new IllegalArgumentException("Source dimensions exceed buffer slice");
        long offset=Math.addExact(source.offset(),Math.addExact(Math.multiplyExact((long)sourceY,rowPitch),Math.multiplyExact((long)sourceX,bytesPerPixel)));
        try (BackendPins pins=new BackendPins(owner,source.buffer(),texture)) {
            owner.commands().copyBufferToImage(owner.buffer(source.buffer()),offset,rowPitch,image(texture),
                    new ImageRegion(mip,layer,destX,destY,width,height));
        }
    }

    void copyTextureToBuffer(GpuTexture texture,GpuBuffer destination,long offset,Runnable callback,int mip,int x,int y,int width,int height) {
        try (BackendPins pins=new BackendPins(owner,texture,destination)) {
            long bytesPerLayer=Math.multiplyExact(Math.multiplyExact((long)width,height),texture.getFormat().blockSize());
            if (offset<0 || offset>destination.size()-Math.multiplyExact(bytesPerLayer,texture.getDepthOrLayers())) {
                throw new IllegalArgumentException("Readback exceeds destination buffer");
            }
            for (int layer=0;layer<texture.getDepthOrLayers();layer++) {
                owner.commands().copyImageToBuffer(image(texture),new ImageRegion(mip,layer,x,y,width,height),
                        owner.buffer(destination),Math.addExact(offset,Math.multiplyExact(bytesPerLayer,layer)),0);
            }
        }
        callback.run();
    }

    void copyTextureToTexture(GpuTexture source,GpuTexture destination,int mip,int destX,int destY,int sourceX,int sourceY,int width,int height) {
        if (source.getFormat()!=destination.getFormat() || source.getDepthOrLayers()!=destination.getDepthOrLayers()) {
            throw new IllegalArgumentException("Image copy formats and layer counts must match");
        }
        try (BackendPins pins=new BackendPins(owner,source,destination)) {
            for (int layer=0;layer<source.getDepthOrLayers();layer++) {
                owner.commands().copyImage(image(source),new ImageRegion(mip,layer,sourceX,sourceY,width,height),
                        image(destination),new ImageRegion(mip,layer,destX,destY,width,height));
            }
        }
    }
}
