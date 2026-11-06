package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Vec3i;
import net.minecraft.util.FastColor;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Writes an item quad with the values NeoForge's {@code putBulkData} computes when it reads the baked colour. */
public final class BlazeBakedQuadWriter {
    private static final boolean LITTLE_ENDIAN=ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN;
    private static final int VERTEX_INTS=8,VERTICES=4,COLOR=3,U=4,V=5,LIGHT=6,NORMAL=7,NORMAL_MASK=0xFFFFFF;
    private static final boolean REUSE_AXES=BlazeOptimization.ITEM_AXES.on();

    private BlazeBakedQuadWriter(){}

    /** Returns false, having written nothing, when the quad or the builder's layout needs the ordinary path. */
    public static boolean write(BlazeBulkVertices target,PoseStack.Pose pose,BakedQuad quad,float red,float green,float blue,float alpha,int light,int overlay){
        int[] data=quad.getVertices();
        if(!LITTLE_ENDIAN||data.length!=VERTEX_INTS*VERTICES)return false;
        long pointer=target.overhaul$reserveEntityVertices(VERTICES);
        if(pointer==0L)return false;
        Matrix4f matrix=pose.pose();
        Matrix3f normals=pose.normal();
        Vector3f normal=new Vector3f();
        int faceNormal=REUSE_AXES?BlazeCubeAxes.of(pose).toward(quad.getDirection()):faceNormal(pose,quad,normal);
        int lastBaked=0,lastPacked=faceNormal;
        for(int at=0;at<data.length;at+=VERTEX_INTS,pointer+=BlazeEntityVertex.BYTES){
            float x=Float.intBitsToFloat(data[at]),y=Float.intBitsToFloat(data[at+1]),z=Float.intBitsToFloat(data[at+2]);
            int color=data[at+COLOR];
            int baked=data[at+NORMAL]&NORMAL_MASK;
            if(baked!=lastBaked){
                lastBaked=baked;
                lastPacked=baked==0?faceNormal:bakedNormal(baked,normals,normal);
            }
            BlazeEntityVertex.put(pointer,
                    matrix.m00()*x+matrix.m10()*y+matrix.m20()*z+matrix.m30(),
                    matrix.m01()*x+matrix.m11()*y+matrix.m21()*z+matrix.m31(),
                    matrix.m02()*x+matrix.m12()*y+matrix.m22()*z+matrix.m32(),
                    tinted(color,red,green,blue,alpha),
                    Float.intBitsToFloat(data[at+U]),Float.intBitsToFloat(data[at+V]),overlay,
                    brightest(light,data[at+LIGHT]),lastPacked);
        }
        return true;
    }
    private static int faceNormal(PoseStack.Pose pose,BakedQuad quad,Vector3f scratch){
        Vec3i direction=quad.getDirection().getNormal();
        return BlazeCubeMesh.packed(pose,direction.getX(),direction.getY(),direction.getZ(),scratch);
    }
    private static int tinted(int baked,float red,float green,float blue,float alpha){
        int a=(int)((alpha*(float)(baked>>>24)/255f)*255);
        int r=(int)((float)(baked&255)*red),g=(int)((float)(baked>>8&255)*green),b=(int)((float)(baked>>16&255)*blue);
        return FastColor.ABGR32.fromArgb32(FastColor.ARGB32.color(a,r,g,b));
    }
    private static int brightest(int light,int baked){
        return Math.max(light&0xFFFF,baked&0xFFFF)|Math.max(light>>16&0xFFFF,baked>>>16)<<16;
    }
    private static int bakedNormal(int baked,Matrix3f normals,Vector3f scratch){
        scratch.set((byte)baked/127f,(byte)(baked>>8)/127f,(byte)(baked>>16)/127f).mul(normals);
        return BlazeEntityVertex.packNormal(scratch.x,scratch.y,scratch.z);
    }
}
