package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A model cube flattened once, so each frame transforms plain arrays into one reserved vertex range. */
public final class BlazeCubeMesh {
    private static final boolean LITTLE_ENDIAN=ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN;
    private static final int VERTICES_PER_QUAD=4,VERTEX_BYTES=36;
    private static final int COLOR=12,UV=16,OVERLAY=24,LIGHT=28,NORMAL=32;
    private static final int NORMAL_FLOATS=3,VERTEX_FLOATS=5,QUAD_FLOATS=NORMAL_FLOATS+VERTICES_PER_QUAD*VERTEX_FLOATS;
    private static final BlazeCubeMesh UNSUPPORTED=new BlazeCubeMesh(null);
    private static final Logger LOGGER=LoggerFactory.getLogger(BlazeCubeMesh.class);
    private static boolean reported;
    private final float[] quads;

    private BlazeCubeMesh(float[] quads){this.quads=quads;}

    /** Flattens a cube, or returns a mesh that always declines when the cube has an unexpected shape. */
    public static BlazeCubeMesh of(ModelPart.Cube cube){
        if(!LITTLE_ENDIAN)return UNSUPPORTED;
        try{
            // Polygon and Vertex are package-private, and this runs once per cube.
            Object array=field(cube,"polygons");
            int count=Array.getLength(array);
            float[] data=new float[count*QUAD_FLOATS];
            for(int index=0;index<count;index++)if(!flatten(Array.get(array,index),data,index*QUAD_FLOATS))return UNSUPPORTED;
            return new BlazeCubeMesh(data);
        }catch(ReflectiveOperationException|RuntimeException failure){
            if(!reported){reported=true;LOGGER.warn("Model cubes stay on the vanilla path",failure);}
            return UNSUPPORTED;
        }
    }
    private static boolean flatten(Object polygon,float[] data,int start) throws ReflectiveOperationException {
        Object vertices=field(polygon,"vertices");
        if(Array.getLength(vertices)!=VERTICES_PER_QUAD)return false;
        Vector3f normal=(Vector3f)field(polygon,"normal");
        data[start]=normal.x;data[start+1]=normal.y;data[start+2]=normal.z;
        for(int index=0;index<VERTICES_PER_QUAD;index++){
            Object vertex=Array.get(vertices,index);
            Vector3f position=(Vector3f)field(vertex,"pos");
            int at=start+NORMAL_FLOATS+index*VERTEX_FLOATS;
            data[at]=position.x/16f;data[at+1]=position.y/16f;data[at+2]=position.z/16f;
            data[at+3]=(float)field(vertex,"u");data[at+4]=(float)field(vertex,"v");
        }
        return true;
    }
    private static Object field(Object owner,String name) throws ReflectiveOperationException {
        Field field=owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** Writes the same vertices as {@code Cube.compile}, or returns false to leave the cube to it. */
    public boolean write(PoseStack.Pose pose,BlazeBulkVertices target,int light,int overlay,int color){
        float[] data=quads;
        if(data==null)return false;
        if(data.length==0)return true;
        long pointer=target.overhaul$reserveEntityVertices(data.length/QUAD_FLOATS*VERTICES_PER_QUAD);
        if(pointer==0L)return false;
        Matrix4f matrix=pose.pose();
        float m00=matrix.m00(),m01=matrix.m01(),m02=matrix.m02();
        float m10=matrix.m10(),m11=matrix.m11(),m12=matrix.m12();
        float m20=matrix.m20(),m21=matrix.m21(),m22=matrix.m22();
        float m30=matrix.m30(),m31=matrix.m31(),m32=matrix.m32();
        int abgr=FastColor.ABGR32.fromArgb32(color);
        Vector3f normal=new Vector3f();
        for(int at=0;at<data.length;){
            pose.transformNormal(data[at],data[at+1],data[at+2],normal);
            at+=NORMAL_FLOATS;
            int packedNormal=packNormal(normal);
            for(int vertex=0;vertex<VERTICES_PER_QUAD;vertex++,at+=VERTEX_FLOATS,pointer+=VERTEX_BYTES){
                float x=data[at],y=data[at+1],z=data[at+2];
                MemoryUtil.memPutFloat(pointer,m00*x+m10*y+m20*z+m30);
                MemoryUtil.memPutFloat(pointer+4L,m01*x+m11*y+m21*z+m31);
                MemoryUtil.memPutFloat(pointer+8L,m02*x+m12*y+m22*z+m32);
                MemoryUtil.memPutInt(pointer+COLOR,abgr);
                MemoryUtil.memPutFloat(pointer+UV,data[at+3]);
                MemoryUtil.memPutFloat(pointer+UV+4L,data[at+4]);
                MemoryUtil.memPutInt(pointer+OVERLAY,overlay);
                MemoryUtil.memPutInt(pointer+LIGHT,light);
                MemoryUtil.memPutInt(pointer+NORMAL,packedNormal);
            }
        }
        return true;
    }
    private static int packNormal(Vector3f normal){return normalByte(normal.x)|normalByte(normal.y)<<8|normalByte(normal.z)<<16;}
    private static int normalByte(float value){return (int)(Mth.clamp(value,-1f,1f)*127f)&0xFF;}
}
