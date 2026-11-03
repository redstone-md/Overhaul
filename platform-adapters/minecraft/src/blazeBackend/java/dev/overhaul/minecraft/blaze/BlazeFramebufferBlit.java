package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.ImageRegion;

/** Exact unscaled framebuffer copies, including destination scissor and source clipping. */
public final class BlazeFramebufferBlit {
    private BlazeFramebufferBlit(){}
    public static void copy(BlazeRenderer owner,int sx0,int sy0,int sx1,int sy1,int dx0,int dy0,int dx1,int dy1,int mask,int filter){
        if(sx1<=sx0||sy1<=sy0||dx1<=dx0||dy1<=dy0||
                (long)sx1-sx0!=(long)dx1-dx0||(long)sy1-sy0!=(long)dy1-dy0){
            throw new UnsupportedOperationException("Scaled/flipped framebuffer blits require explicit image blit lowering");
        }
        if(filter!=9728&&filter!=9729)throw new IllegalArgumentException("Unknown framebuffer filter");
        if((mask&~(0x4000|0x100))!=0)throw new UnsupportedOperationException("Stencil/unknown framebuffer blit mask");
        if((mask&0x100)!=0&&filter!=9728)throw new IllegalArgumentException("Depth blits require nearest filtering");
        BlazeTarget source=owner.readTarget(),target=owner.currentTarget();
        var raster=owner.drawState().snapshot();
        long left=Math.max(Math.max((long)dx0,0),(long)dx0-sx0),bottom=Math.max(Math.max((long)dy0,0),(long)dy0-sy0);
        long right=Math.min(Math.min((long)dx1,target.width()),(long)dx0+source.width()-sx0);
        long top=Math.min(Math.min((long)dy1,target.height()),(long)dy0+source.height()-sy0);
        if(raster.scissor()){
            left=Math.max(left,raster.sx());bottom=Math.max(bottom,raster.sy());
            right=Math.min(right,(long)raster.sx()+raster.sw());top=Math.min(top,(long)raster.sy()+raster.sh());
        }
        if(right<=left||top<=bottom)return;
        var from=new ImageRegion(0,0,(int)(sx0+left-dx0),(int)(sy0+bottom-dy0),(int)(right-left),(int)(top-bottom));
        var to=new ImageRegion(0,0,(int)left,(int)bottom,(int)(right-left),(int)(top-bottom));
        if((mask&0x4000)!=0)copy(owner,source.color(),from,target.color(),to);
        if((mask&0x100)!=0){
            if(source.depth()==null||target.depth()==null)throw new UnsupportedOperationException("Depth blit without both depth attachments");
            copy(owner,source.depth(),from,target.depth(),to);
        }
    }
    private static void copy(BlazeRenderer owner,GpuImage source,ImageRegion from,GpuImage target,ImageRegion to){
        if(source==target)throw new UnsupportedOperationException("Overlapping/self framebuffer copy requires explicit temporary storage");
        owner.copyImage(source,from,target,to);
    }
}
