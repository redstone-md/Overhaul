package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.ImageRegion;

/** Framebuffer copies: exact ones honour destination scissor and source clipping, scaled or flipped ones use an image blit. */
public final class BlazeFramebufferBlit {
    private static final int NEAREST=9728,LINEAR=9729,COLOR=0x4000,DEPTH=0x100;
    private BlazeFramebufferBlit(){}
    public static void copy(BlazeRenderer owner,int sx0,int sy0,int sx1,int sy1,int dx0,int dy0,int dx1,int dy1,int mask,int filter){
        // A raw framebuffer has no image to copy to or from.
        if(owner.drawingForeign()||owner.readingForeign())return;
        if(owner.readTarget()==owner.currentTarget()){
            BlazeGlBoundary.reportOnce("self blit","A framebuffer blit had the same source and destination and was skipped");
            return;
        }
        if(filter!=NEAREST&&filter!=LINEAR)throw new IllegalArgumentException("Unknown framebuffer filter");
        if((mask&~(COLOR|DEPTH))!=0)throw new UnsupportedOperationException("Stencil/unknown framebuffer blit mask");
        if((mask&DEPTH)!=0&&filter!=NEAREST)throw new IllegalArgumentException("Depth blits require nearest filtering");
        // OpenGL treats an empty rectangle as nothing to do.
        if(sx1==sx0||sy1==sy0||dx1==dx0||dy1==dy0)return;
        if(sx1<sx0||sy1<sy0||dx1<dx0||dy1<dy0||(long)sx1-sx0!=(long)dx1-dx0||(long)sy1-sy0!=(long)dy1-dy0){
            scaled(owner,new int[]{sx0,sy0,sx1,sy1},new int[]{dx0,dy0,dx1,dy1},mask,filter);
            return;
        }
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
        if((mask&COLOR)!=0)copy(owner,source.color(),from,target.color(),to);
        if((mask&DEPTH)!=0){
            if(source.depth()==null||target.depth()==null)throw new UnsupportedOperationException("Depth blit without both depth attachments");
            copy(owner,source.depth(),from,target.depth(),to);
        }
    }
    private static void scaled(BlazeRenderer owner,int[] from,int[] to,int mask,int filter){
        BlazeTarget source=owner.readTarget(),target=owner.currentTarget();
        if(owner.drawState().snapshot().scissor())BlazeGlBoundary.reportOnce("scaled blit scissor","A scaled framebuffer blit ran under a scissor; the scissor was not applied");
        // Vulkan rejects corners outside the image, where OpenGL clips and keeps the scale.
        if(!clip(from,to,source.width(),source.height())||!clip(to,from,target.width(),target.height()))return;
        if((mask&COLOR)!=0)owner.blitImage(source.color(),from,target.color(),to,filter);
        if((mask&DEPTH)!=0){
            if(source.depth()==null||target.depth()==null)throw new UnsupportedOperationException("Depth blit without both depth attachments");
            owner.blitImage(source.depth(),from,target.depth(),to,NEAREST);
        }
    }
    /** Pulls the corners of {@code rectangle} inside its image and moves {@code other} by the same fraction. */
    private static boolean clip(int[] rectangle,int[] other,int width,int height){
        return clipAxis(rectangle,other,0,width)&&clipAxis(rectangle,other,1,height);
    }
    private static boolean clipAxis(int[] rectangle,int[] other,int axis,int size){
        int low=rectangle[axis],high=rectangle[axis+2],otherLow=other[axis],otherHigh=other[axis+2];
        double scale=(double)((long)otherHigh-otherLow)/((long)high-low);
        int clippedLow=Math.max(0,Math.min(size,low)),clippedHigh=Math.max(0,Math.min(size,high));
        if(clippedLow==clippedHigh)return false;
        rectangle[axis]=clippedLow;rectangle[axis+2]=clippedHigh;
        other[axis]=(int)Math.round(otherLow+((long)clippedLow-low)*scale);
        other[axis+2]=(int)Math.round(otherLow+((long)clippedHigh-low)*scale);
        return other[axis]!=other[axis+2];
    }
    private static void copy(BlazeRenderer owner,GpuImage source,ImageRegion from,GpuImage target,ImageRegion to){
        if(source==target)throw new UnsupportedOperationException("Overlapping/self framebuffer copy requires explicit temporary storage");
        owner.copyImage(source,from,target,to);
    }
}
