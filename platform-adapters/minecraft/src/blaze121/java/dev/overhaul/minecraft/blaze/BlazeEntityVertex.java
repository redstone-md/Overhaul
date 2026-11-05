package dev.overhaul.minecraft.blaze;

import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/** The memory layout of one vertex in the game's entity format, written directly into a reserved range. */
final class BlazeEntityVertex {
    static final int BYTES=36;
    private static final int COLOR=12,UV=16,OVERLAY=24,LIGHT=28,NORMAL=32;

    private BlazeEntityVertex(){}
    static void put(long pointer,float x,float y,float z,int abgr,float u,float v,int overlay,int light,int normal){
        MemoryUtil.memPutFloat(pointer,x);
        MemoryUtil.memPutFloat(pointer+4L,y);
        MemoryUtil.memPutFloat(pointer+8L,z);
        MemoryUtil.memPutInt(pointer+COLOR,abgr);
        MemoryUtil.memPutFloat(pointer+UV,u);
        MemoryUtil.memPutFloat(pointer+UV+4L,v);
        MemoryUtil.memPutInt(pointer+OVERLAY,overlay);
        MemoryUtil.memPutInt(pointer+LIGHT,light);
        MemoryUtil.memPutInt(pointer+NORMAL,normal);
    }
    static int packNormal(float x,float y,float z){return normalByte(x)|normalByte(y)<<8|normalByte(z)<<16;}
    private static int normalByte(float value){return (int)(Mth.clamp(value,-1f,1f)*127f)&0xFF;}
}
