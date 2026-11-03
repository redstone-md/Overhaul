package dev.overhaul.minecraft.smoke;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.vulkan.PackTextureData;

import java.util.concurrent.CompletableFuture;

/** Borrows a live texture until its GPU callback copies all pixels into owned Java bytes. */
final class MinecraftTextureReadback {
    private MinecraftTextureReadback() { }

    static CompletableFuture<PackTextureData> capture(GpuTexture texture,PackTextureData.Filter filter) {
        RenderSystem.assertOnRenderThread();
        if (texture.getFormat()!=GpuFormat.RGBA8_UNORM || (texture.usage()&GpuTexture.USAGE_COPY_SRC)==0) {
            throw new IllegalArgumentException("Smoke readback requires an RGBA8 COPY_SRC texture: "+texture.getLabel());
        }
        int width=texture.getWidth(0),height=texture.getHeight(0);
        int bytes=Math.multiplyExact(Math.multiplyExact(width,height),4);
        if (bytes>67_108_864) throw new IllegalArgumentException("Texture exceeds smoke readback budget");
        GpuBuffer readback=RenderSystem.getDevice().createBuffer(()->"Overhaul model texture readback",
                GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,bytes);
        CompletableFuture<PackTextureData> result=new CompletableFuture<>();
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture,readback,0,()-> {
                try (readback; var mapping=readback.map(true,false)) {
                    byte[] pixels=new byte[bytes]; mapping.data().get(pixels);
                    result.complete(new PackTextureData(width,height,pixels,filter,PackTextureData.Wrap.CLAMP));
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            },0);
        } catch (RuntimeException|Error failure) { readback.close(); throw failure; }
        return result;
    }
}
