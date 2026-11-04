package dev.overhaul.minecraft.shader;

import dev.overhaul.shader.compat.UniformBlock;
import org.joml.Matrix4f;
import java.nio.ByteBuffer;

/** One frame's detached cascade cameras; queued draws read these values, never a later camera selection. */
public final class CascadeShadowFrame {
    /** std140 size of the receiver block: four matrices, splits, starts, texel sizes and the cascade count. */
    public static final int RECEIVER_BYTES=320;
    private final float[] splits,starts;
    private final float[][] projections,projectionInverses,views,viewInverses,receivers;
    private final int resolution;
    CascadeShadowFrame(int resolution,float[] splits,float[] starts,float[][] projections,float[][] views,float[][] receivers){
        this.resolution=resolution;this.splits=splits.clone();this.starts=starts.clone();
        this.projections=copy(projections);this.views=copy(views);this.receivers=copy(receivers);
        projectionInverses=inverses(projections);viewInverses=inverses(views);
    }
    public int count(){return splits.length;}
    /** Overlays one cascade on the shared values; unreflected names stay absent, as in the frame uniforms. */
    public void fill(int cascade,UniformBlock values){
        var names=values.bindings().uniforms();
        if(names.containsKey("shadowProjection"))values.setMatrix("shadowProjection",4,projections[cascade]);
        if(names.containsKey("shadowProjectionInverse"))values.setMatrix("shadowProjectionInverse",4,projectionInverses[cascade]);
        if(names.containsKey("shadowModelView"))values.setMatrix("shadowModelView",4,views[cascade]);
        if(names.containsKey("shadowModelViewInverse"))values.setMatrix("shadowModelViewInverse",4,viewInverses[cascade]);
    }
    /** Writes the receiver block at absolute offsets into a zeroed native-order buffer; unused cascades stay zero. */
    public void writeReceiver(ByteBuffer target){
        var floats=target.asFloatBuffer();
        for(int i=0;i<count();i++){
            floats.put(i*16,receivers[i]);
            floats.put(64+i,splits[i]);floats.put(68+i,starts[i]);floats.put(72+i,2f/(projections[i][0]*resolution));
        }
        target.putInt(304,count());
    }
    private static float[][] copy(float[][] source){float[][] result=new float[source.length][];for(int i=0;i<source.length;i++)result[i]=source[i].clone();return result;}
    private static float[][] inverses(float[][] source){
        float[][] result=new float[source.length][16];Matrix4f matrix=new Matrix4f();
        for(int i=0;i<source.length;i++)matrix.set(source[i]).invert().get(result[i]);return result;
    }
}
