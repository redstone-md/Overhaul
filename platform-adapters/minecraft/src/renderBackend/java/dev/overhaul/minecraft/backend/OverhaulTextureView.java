package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.overhaul.vulkan.GpuImageView;
import static org.lwjgl.vulkan.VK10.*;

/** A view pins its parent texture; sampled depth/stencil formats expose depth only. */
public final class OverhaulTextureView extends BackendResource implements GpuTextureView {
    private final OverhaulTexture texture;
    private final int baseMip,mips;
    private AutoCloseable parentPin;
    private GpuImageView view;

    OverhaulTextureView(OverhaulDeviceBackend owner,OverhaulTexture texture,int baseMip,int mips) {
        super(owner);
        this.texture=texture; this.baseMip=baseMip; this.mips=mips;
        try {
            parentPin=texture.pin();
            int layers=texture.getDepthOrLayers();
            boolean cube=(texture.usage()&GpuTexture.USAGE_CUBEMAP_COMPATIBLE)!=0;
            if (cube&&layers!=6) throw new UnsupportedOperationException("Cube arrays are not exposed by this backend");
            int type=cube?VK_IMAGE_VIEW_TYPE_CUBE:layers==1?VK_IMAGE_VIEW_TYPE_2D:VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            int aspects=texture.getFormat().hasDepthAspect()?VK_IMAGE_ASPECT_DEPTH_BIT:OverhaulFormats.aspects(texture.getFormat());
            view=texture.image().view(baseMip,mips,0,layers,type,aspects);
        } catch (RuntimeException|Error failure) { close(); throw failure; }
    }

    public GpuImageView nativeView() { requireNativeAlive(); return view; }
    @Override public GpuTexture texture() { return texture; }
    @Override public int baseMipLevel() { return baseMip; }
    @Override public int mipLevels() { return mips; }
    @Override public int getWidth(int mip) { checkMip(mip); return texture.getWidth(baseMip+mip); }
    @Override public int getHeight(int mip) { checkMip(mip); return texture.getHeight(baseMip+mip); }
    private void checkMip(int mip) { if (mip<0||mip>=mips) throw new IllegalArgumentException("Invalid view mip level"); }
    @Override protected void destroyNative() {
        try { if (view!=null) view.close(); }
        finally {
            if (parentPin!=null) try { parentPin.close(); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Cannot release texture view",failure); }
        }
    }
}
