package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Bounded frame-owned quad stream; UV/decal evaluation and normal transformation belong to the collector. */
public final class ModelQuadInstanceWriter {
    public static final int IDENTITY=1<<12,TRANSLATION=1<<13,FMA=1<<14;
    private final ByteBuffer target;
    private final int maximum;
    private int written;
    public ModelQuadInstanceWriter(ByteBuffer target,int maximum){
        this.target=Objects.requireNonNull(target).order(ByteOrder.nativeOrder());this.maximum=maximum;
        if(maximum<0||target.remaining()<(long)maximum*InstancedModelPipelines.INSTANCE_STRIDE)throw new IllegalArgumentException("Model instance storage is too small");
    }
    public void write(PoseStack.Pose pose,ModelQuadGeometry quad,float[] uv8,Vector3fc normal,int argb,int light,int overlay){
        if(written==maximum)throw new IllegalStateException("Model quad stream exceeds its captured capacity");
        Objects.requireNonNull(pose);Objects.requireNonNull(quad);Objects.requireNonNull(normal);
        if(uv8==null||uv8.length<8)throw new IllegalArgumentException("Four exact UV pairs required");
        Matrix4f m=pose.pose();
        target.putFloat(m.m00()).putFloat(m.m10()).putFloat(m.m20()).putFloat(m.m30());
        target.putFloat(m.m01()).putFloat(m.m11()).putFloat(m.m21()).putFloat(m.m31());
        target.putFloat(m.m02()).putFloat(m.m12()).putFloat(m.m22()).putFloat(m.m32());
        for(int axis=0;axis<3;axis++)target.putFloat(quad.min(axis));
        for(int axis=0;axis<3;axis++)target.putFloat(quad.max(axis));
        for(int component=0;component<8;component++)target.putFloat(uv8[component]);
        target.putInt(packNormal(normal)).putInt(argb).putInt(light).putInt(overlay).putInt(quad.cornerMask()|flags(m));written++;
    }
    private static int packNormal(Vector3fc normal){return component(normal.x())|component(normal.y())<<8|component(normal.z())<<16;}
    private static int component(float value){return(int)(Mth.clamp(value,-1f,1f)*127f)&255;}
    private static int flags(Matrix4f matrix){
        int result=org.joml.Runtime.HAS_Math_fma?FMA:0;
        if((matrix.properties()&4)!=0)result|=IDENTITY;else if((matrix.properties()&8)!=0)result|=TRANSLATION;
        return result;
    }
    public int written(){return written;}
    public void finish(){finish(maximum);}
    public void finish(int actual){if(actual<0||actual>maximum||written!=actual)throw new IllegalStateException("Model quad stream changed during preparation: "+written+" != "+actual);}
}
