package dev.overhaul.vulkan;

/** Immutable camera; world subtraction happens in double before section-local float transformation. */
public final class ModelCamera {
    private final float[] rotationProjection;
    private final double x,y,z;

    private ModelCamera(float[] rotationProjection,double x,double y,double z,boolean openGl) {
        if (rotationProjection.length!=16 || !Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) throw new IllegalArgumentException("Invalid camera");
        this.rotationProjection=rotationProjection.clone(); this.x=x; this.y=y; this.z=z;
        for (float value:this.rotationProjection) if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite projection");
        if (openGl) for (int column=0;column<4;column++) {
            int offset=column*4;
            this.rotationProjection[offset+1]=-this.rotationProjection[offset+1];
            this.rotationProjection[offset+2]=.5f*(this.rotationProjection[offset+2]+this.rotationProjection[offset+3]);
        }
    }
    /** Matrix contains camera rotation and projection, with world camera translation omitted. */
    public static ModelCamera vulkan(float[] rotationProjection,double x,double y,double z){return new ModelCamera(rotationProjection,x,y,z,false);}
    public static ModelCamera openGl(float[] rotationProjection,double x,double y,double z){return new ModelCamera(rotationProjection,x,y,z,true);}
    public double x(){return x;} public double y(){return y;} public double z(){return z;}

    public float[] transform(double originX,double originY,double originZ) {
        float[] result=rotationProjection.clone();
        double dx=originX-x,dy=originY-y,dz=originZ-z;
        for (int row=0;row<4;row++) {
            double translated=rotationProjection[row]*dx+rotationProjection[4+row]*dy+rotationProjection[8+row]*dz+rotationProjection[12+row];
            result[12+row]=(float)translated;
            if (!Float.isFinite(result[12+row])) throw new IllegalArgumentException("Model origin outside finite camera range");
        }
        return result;
    }
}
