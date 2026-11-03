package dev.overhaul.minecraft.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;

/** Converts the pinned game's external pixel formats without treating packed ints as host-independent RGBA bytes. */
final class BlazePixels {
    private BlazePixels(){}
    static int components(int format){return switch(format){
        case GL_RED,GL_DEPTH_COMPONENT->1;case 33319->2;case GL_RGB,GL_BGR->3;case GL_RGBA,GL_BGRA->4;
        default->throw new UnsupportedOperationException("Unsupported pixel format: "+format);
    };}
    static int bytes(int format,int type){
        int components=components(format);
        if(type==GL_UNSIGNED_BYTE)return components;
        if(type==GL_FLOAT)return Math.multiplyExact(components,4);
        if((type==GL_UNSIGNED_INT_8_8_8_8||type==GL_UNSIGNED_INT_8_8_8_8_REV)&&components==4)return 4;
        throw new UnsupportedOperationException("Unsupported pixel component type: "+type);
    }
    static ByteBuffer unpack(ByteBuffer input,BlazePixelStore.Layout layout,int width,int height,int format,int type,int channels,int storageBytes){
        if(input.remaining()<layout.bytes())throw new IllegalArgumentException("Pixel source is truncated");
        var source=input.slice().order(ByteOrder.nativeOrder());var output=MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width,height),storageBytes));
        int bytes=bytes(format,type);
        try{
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                int pixel=read(source,layout.offset()+y*layout.stride()+x*bytes,format,type);output.put((byte)(pixel>>>24));
                if(storageBytes>1)output.put((byte)(channels>1?pixel>>>16:0));
                if(storageBytes>2)output.put((byte)(channels>2?pixel>>>8:0));
                if(storageBytes>3)output.put((byte)(channels==4?pixel:255));
            }
            return output.flip();
        }catch(RuntimeException|Error failure){MemoryUtil.memFree(output);throw failure;}
    }
    static void pack(ByteBuffer rgba,boolean depth,int storageBytes,ByteBuffer destination,BlazePixelStore.Layout layout,int width,int height,int format,int type){
        if(destination.remaining()<layout.bytes())throw new IllegalArgumentException("Pixel destination is truncated");
        var target=destination.slice().order(ByteOrder.nativeOrder());var source=rgba.slice().order(ByteOrder.nativeOrder());int stride=bytes(format,type);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int offset=(y*width+x)*storageBytes,pixel;
            if(depth){int value=normalized(source.getFloat(offset));pixel=value<<24|value<<16|value<<8|255;}
            else pixel=(source.get(offset)&255)<<24|(storageBytes>1?source.get(offset+1)&255:0)<<16|
                    (storageBytes>2?source.get(offset+2)&255:0)<<8|(storageBytes>3?source.get(offset+3)&255:255);
            write(target,layout.offset()+y*layout.stride()+x*stride,format,type,pixel,depth?source.getFloat(offset):0);
        }
    }
    private static int read(ByteBuffer source,int offset,int format,int type){
        int count=components(format),pixel=255;
        if(type==GL_UNSIGNED_INT_8_8_8_8||type==GL_UNSIGNED_INT_8_8_8_8_REV){
            int packed=source.getInt(offset);pixel=type==GL_UNSIGNED_INT_8_8_8_8?packed:Integer.reverseBytes(packed);
        }else for(int component=0;component<count;component++){
            int value=type==GL_UNSIGNED_BYTE?source.get(offset+component)&255:normalized(source.getFloat(offset+component*4));
            int shift=24-component*8;pixel=(pixel&~(255<<shift))|value<<shift;
        }
        return format==GL_BGRA||format==GL_BGR?swapRedBlue(pixel):pixel;
    }
    private static void write(ByteBuffer target,int offset,int format,int type,int pixel,float depth){
        if(format==GL_BGRA||format==GL_BGR)pixel=swapRedBlue(pixel);
        if(type==GL_UNSIGNED_INT_8_8_8_8||type==GL_UNSIGNED_INT_8_8_8_8_REV){target.putInt(offset,type==GL_UNSIGNED_INT_8_8_8_8?pixel:Integer.reverseBytes(pixel));return;}
        for(int component=0;component<components(format);component++){
            int value=pixel>>>(24-component*8)&255;
            if(type==GL_UNSIGNED_BYTE)target.put(offset+component,(byte)value);
            else target.putFloat(offset+component*4,format==GL_DEPTH_COMPONENT?depth:value/255f);
        }
    }
    private static int swapRedBlue(int pixel){return (pixel&0x00ff00ff)|(pixel>>>8&255)<<24|(pixel>>>24&255)<<8;}
    private static int normalized(float value){return Math.round(Math.max(0,Math.min(1,value))*255);}
}
