package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.lwjgl.system.MemoryUtil;

/**
 * Replaces the per-pixel double arithmetic and checked pixel accessors of interpolated animations with fixed-point
 * blending on raw memory. Colours may differ from vanilla by one step of rounding. Returns false to keep the vanilla loop.
 */
public final class BlazeSpriteInterpolation {
    private static final String CONTENTS="net.minecraft.client.renderer.texture.SpriteContents";
    private static final VarHandle OUTER,ACTIVE,MIPS,FRAME,SUB_FRAME,ANIMATION,FRAMES,ROW_SIZE,INDEX,TIME,PIXELS;
    private static final MethodHandle UPLOAD;
    private static final boolean AVAILABLE;
    static{
        VarHandle outer=null,active=null,mips=null,frame=null,subFrame=null,animation=null,frames=null,rowSize=null,index=null,time=null,pixels=null;
        MethodHandle upload=null;boolean available=false;
        try{
            MethodHandles.Lookup own=MethodHandles.lookup(),contents=MethodHandles.privateLookupIn(SpriteContents.class,own);
            ClassLoader loader=SpriteContents.class.getClassLoader();
            Class<?> data=Class.forName(CONTENTS+"$InterpolationData",false,loader),ticker=Class.forName(CONTENTS+"$Ticker",false,loader);
            Class<?> animated=Class.forName(CONTENTS+"$AnimatedTexture",false,loader),info=Class.forName(CONTENTS+"$FrameInfo",false,loader);
            outer=field(contents,data,SpriteContents.class,"this$0","f_244452_");
            active=field(contents,data,NativeImage[].class,"activeFrame","f_244527_");
            mips=field(contents,SpriteContents.class,NativeImage[].class,"byMipLevel","f_243731_");
            frame=field(contents,ticker,int.class,"frame","f_244631_");
            subFrame=field(contents,ticker,int.class,"subFrame","f_244511_");
            animation=field(contents,ticker,animated,"animationInfo","f_243921_");
            frames=field(contents,animated,List.class,"frames","f_243714_");
            rowSize=field(contents,animated,int.class,"frameRowSize","f_244229_");
            index=field(contents,info,int.class,"index","f_243751_");
            time=field(contents,info,int.class,"time","f_244553_");
            pixels=field(MethodHandles.privateLookupIn(NativeImage.class,own),NativeImage.class,long.class,"pixels","f_84964_");
            upload=method(contents,SpriteContents.class,MethodType.methodType(void.class,int.class,int.class,int.class,int.class,NativeImage[].class),"upload","m_247381_");
            available=true;
        }catch(ReflectiveOperationException|RuntimeException failure){
            System.getLogger("overhaul").log(System.Logger.Level.WARNING,"Vanilla sprite interpolation stays in use",failure);
        }
        OUTER=outer;ACTIVE=active;MIPS=mips;FRAME=frame;SUB_FRAME=subFrame;ANIMATION=animation;FRAMES=frames;ROW_SIZE=rowSize;INDEX=index;TIME=time;PIXELS=pixels;UPLOAD=upload;AVAILABLE=available;
    }
    private BlazeSpriteInterpolation(){}

    /** Mojang names first; Forge 1.20.1 runs on SRG names, so each lookup carries its SRG name as the fallback. */
    private static VarHandle field(MethodHandles.Lookup lookup,Class<?> owner,Class<?> type,String... names)throws ReflectiveOperationException{
        ReflectiveOperationException missing=null;
        for(String name:names)try{return lookup.findVarHandle(owner,name,type);}catch(NoSuchFieldException failure){missing=failure;}
        throw missing;
    }
    private static MethodHandle method(MethodHandles.Lookup lookup,Class<?> owner,MethodType type,String... names)throws ReflectiveOperationException{
        ReflectiveOperationException missing=null;
        for(String name:names)try{return lookup.findVirtual(owner,name,type);}catch(NoSuchMethodException failure){missing=failure;}
        throw missing;
    }

    public static boolean upload(Object data,int x,int y,Object ticker){
        if(!AVAILABLE)return false;
        SpriteContents contents=(SpriteContents)OUTER.get(data);
        Object animation=ANIMATION.get(ticker);
        List<?> frames=(List<?>)FRAMES.get(animation);
        int position=(int)FRAME.get(ticker);
        Object current=frames.get(position);
        int from=(int)INDEX.get(current),to=(int)INDEX.get(frames.get((position+1)%frames.size()));
        if(from==to)return true;
        double share=1.0-(double)(int)SUB_FRAME.get(ticker)/(double)(int)TIME.get(current);
        NativeImage[] sources=(NativeImage[])MIPS.get(contents),targets=(NativeImage[])ACTIVE.get(data);
        int rowSize=(int)ROW_SIZE.get(animation),width=contents.width(),height=contents.height();
        int fromX=from%rowSize*width,fromY=from/rowSize*height,toX=to%rowSize*width,toY=to/rowSize*height;
        for(int mip=0;mip<targets.length;mip++)if(!usable(sources[mip],targets[mip],fromX,fromY,toX,toY,width,height,mip))return false;
        int weight=(int)(share*256);
        for(int mip=0;mip<targets.length;mip++){
            int w=width>>mip,h=height>>mip;
            if(w>=1&&h>=1)blend(sources[mip],targets[mip],fromX>>mip,fromY>>mip,toX>>mip,toY>>mip,w,h,weight);
        }
        try{UPLOAD.invoke(contents,x,y,0,0,targets);}
        catch(RuntimeException|Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException(failure);}
        return true;
    }
    private static boolean usable(NativeImage source,NativeImage target,int fromX,int fromY,int toX,int toY,int width,int height,int mip){
        int w=width>>mip,h=height>>mip;
        if(w<1||h<1)return true;
        return source.format()==NativeImage.Format.RGBA&&target.format()==NativeImage.Format.RGBA
                &&(long)PIXELS.get(source)!=0&&(long)PIXELS.get(target)!=0&&target.getWidth()>=w&&target.getHeight()>=h
                &&Math.max(fromX>>mip,toX>>mip)+w<=source.getWidth()&&Math.max(fromY>>mip,toY>>mip)+h<=source.getHeight();
    }
    private static void blend(NativeImage source,NativeImage target,int fromX,int fromY,int toX,int toY,int width,int height,int weight){
        long base=(long)PIXELS.get(source),out=(long)PIXELS.get(target);
        int rest=256-weight,sourceStride=source.getWidth()*4,targetStride=target.getWidth()*4;
        for(int row=0;row<height;row++){
            long a=base+(long)(fromY+row)*sourceStride+fromX*4L,b=base+(long)(toY+row)*sourceStride+toX*4L,c=out+(long)row*targetStride;
            for(int column=0;column<width;column++,a+=4,b+=4,c+=4){
                int first=MemoryUtil.memGetInt(a),second=MemoryUtil.memGetInt(b);
                int outer=((first&0xFF00FF)*weight+(second&0xFF00FF)*rest)>>>8&0xFF00FF;
                int middle=((first&0xFF00)*weight+(second&0xFF00)*rest)>>>8&0xFF00;
                MemoryUtil.memPutInt(c,first&0xFF000000|outer|middle);
            }
        }
    }
}
