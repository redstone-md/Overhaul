package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.FastColor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A model cube flattened once, so each frame transforms plain arrays into one reserved vertex range. */
public final class BlazeCubeMesh {
    private static final boolean LITTLE_ENDIAN=ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN;
    private static final int VERTICES_PER_QUAD=4;
    private static final int NORMAL_FLOATS=3,VERTEX_FLOATS=5,QUAD_FLOATS=NORMAL_FLOATS+VERTICES_PER_QUAD*VERTEX_FLOATS;
    /** A quad's normal when it lies on an axis: 0-2 for +x +y +z, 3-5 for the opposite, else {@link #OFF_AXIS}. */
    private static final byte OFF_AXIS=-1;
    private static final BlazeCubeMesh UNSUPPORTED=new BlazeCubeMesh(null,null);
    private static final Logger LOGGER=LoggerFactory.getLogger(BlazeCubeMesh.class);
    private static boolean reported;
    private final float[] quads;
    private final byte[] axes;

    private BlazeCubeMesh(float[] quads,byte[] axes){this.quads=quads;this.axes=axes;}

    /** Flattens a cube, or returns a mesh that always declines when the cube has an unexpected shape. */
    public static BlazeCubeMesh of(ModelPart.Cube cube){
        if(!LITTLE_ENDIAN)return UNSUPPORTED;
        try{
            // Polygon and Vertex are package-private, and this runs once per cube.
            Object array=field(cube,"polygons");
            int count=Array.getLength(array);
            float[] data=new float[count*QUAD_FLOATS];
            for(int index=0;index<count;index++)if(!flatten(Array.get(array,index),data,index*QUAD_FLOATS))return UNSUPPORTED;
            byte[] axes=new byte[count];
            for(int index=0;index<count;index++)axes[index]=axis(data[index*QUAD_FLOATS],data[index*QUAD_FLOATS+1],data[index*QUAD_FLOATS+2]);
            return new BlazeCubeMesh(data,axes);
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
    private static byte axis(float x,float y,float z){
        if(y==0f&&z==0f&&Math.abs(x)==1f)return (byte)(x>0f?0:3);
        if(x==0f&&z==0f&&Math.abs(y)==1f)return (byte)(y>0f?1:4);
        if(x==0f&&y==0f&&Math.abs(z)==1f)return (byte)(z>0f?2:5);
        return OFF_AXIS;
    }
    private static int packed(PoseStack.Pose pose,float x,float y,float z,Vector3f scratch){
        pose.transformNormal(x,y,z,scratch);
        return BlazeEntityVertex.packNormal(scratch.x,scratch.y,scratch.z);
    }
    private static int opposite(int packed){
        return -(byte)packed&0xFF|(-(byte)(packed>>8)&0xFF)<<8|(-(byte)(packed>>16)&0xFF)<<16;
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
        // Six faces, three directions: the opposite of a transformed and packed normal is that value negated.
        int alongX=packed(pose,1f,0f,0f,normal),alongY=packed(pose,0f,1f,0f,normal),alongZ=packed(pose,0f,0f,1f,normal);
        byte[] faces=axes;
        for(int at=0,quad=0;at<data.length;quad++){
            int packedNormal=switch(faces[quad]){
                case 0->alongX;
                case 1->alongY;
                case 2->alongZ;
                case 3->opposite(alongX);
                case 4->opposite(alongY);
                case 5->opposite(alongZ);
                default->packed(pose,data[at],data[at+1],data[at+2],normal);
            };
            at+=NORMAL_FLOATS;
            for(int vertex=0;vertex<VERTICES_PER_QUAD;vertex++,at+=VERTEX_FLOATS,pointer+=BlazeEntityVertex.BYTES){
                float x=data[at],y=data[at+1],z=data[at+2];
                BlazeEntityVertex.put(pointer,m00*x+m10*y+m20*z+m30,m01*x+m11*y+m21*z+m31,m02*x+m12*y+m22*z+m32,abgr,data[at+3],data[at+4],overlay,light,packedNormal);
            }
        }
        return true;
    }
}
