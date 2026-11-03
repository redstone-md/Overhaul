package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.ImageFormat;
import java.util.Objects;
import static org.lwjgl.vulkan.VK10.*;

/** Game texture metadata backed exclusively by an owned VMA image. */
public final class OverhaulTexture extends BackendResource implements GpuTexture {
    private final String label;
    private final int usage,width,height,layers,mips;
    private final GpuFormat format;
    private GpuImage image;

    OverhaulTexture(OverhaulDeviceBackend owner,String label,int usage,GpuFormat format,
            int width,int height,int layers,int mips) {
        super(owner);
        this.label=Objects.requireNonNullElse(label,"overhaul texture");
        this.usage=usage; this.format=format;
        this.width=width; this.height=height; this.layers=layers; this.mips=mips;
        try {
            Objects.requireNonNull(format,"format");
            int nativeUsage=VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            if ((usage&USAGE_TEXTURE_BINDING)!=0) nativeUsage|=VK_IMAGE_USAGE_SAMPLED_BIT;
            if ((usage&USAGE_RENDER_ATTACHMENT)!=0) nativeUsage|=format.hasColorAspect()
                    ?VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT:VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
            int flags=(usage&USAGE_CUBEMAP_COMPATIBLE)!=0?VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT:0;
            image=owner.allocator().createImage(ImageFormat.fromVk(OverhaulFormats.format(format)),
                    width,height,mips,layers,nativeUsage,flags);
        } catch (RuntimeException|Error failure) { close(); throw failure; }
    }

    public GpuImage image() { requireNativeAlive(); return image; }
    @Override public int getWidth(int mip) { checkMip(mip); return Math.max(1,width>>mip); }
    @Override public int getHeight(int mip) { checkMip(mip); return Math.max(1,height>>mip); }
    @Override public int getDepthOrLayers() { return layers; }
    @Override public int getMipLevels() { return mips; }
    @Override public GpuFormat getFormat() { return format; }
    @Override public int usage() { return usage; }
    @Override public String getLabel() { return label; }
    private void checkMip(int mip) { if (mip<0||mip>=mips) throw new IllegalArgumentException("Invalid mip level"); }
    @Override protected void destroyNative() { if (image!=null) image.close(); }
}
