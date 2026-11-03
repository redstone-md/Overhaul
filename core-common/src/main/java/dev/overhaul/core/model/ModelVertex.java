package dev.overhaul.core.model;

/** Immutable captured baked vertex. RGB already includes source tint, AO and directional shading. */
public final class ModelVertex {
    private final float x,y,z,u,v,lightU,lightV,normalX,normalY,normalZ;
    private final int rgba;

    public ModelVertex(float x,float y,float z,float u,float v,int rgba,float lightU,float lightV,float normalX,float normalY,float normalZ) {
        if (!finite(x)||!finite(y)||!finite(z)||!unit(u)||!unit(v)||!unit(lightU)||!unit(lightV)
                ||!normal(normalX)||!normal(normalY)||!normal(normalZ)) throw new IllegalArgumentException("Invalid baked vertex");
        this.x=x; this.y=y; this.z=z; this.u=u; this.v=v; this.rgba=rgba;
        this.lightU=lightU; this.lightV=lightV; this.normalX=normalX; this.normalY=normalY; this.normalZ=normalZ;
    }
    public ModelVertex(float x,float y,float z,float u,float v,int rgba,int packedLight,float normalX,float normalY,float normalZ) {
        this(x,y,z,u,v,rgba,lightCoordinate(packedLight&65535),lightCoordinate((packedLight>>>16)&65535),normalX,normalY,normalZ);
    }
    private static float lightCoordinate(int coordinate) {
        if (coordinate>240) throw new IllegalArgumentException("Packed light must contain 0..240 block/sky coordinates");
        return (coordinate+8)/256f;
    }
    public static int rgbaFromArgb(int argb) { return ((argb>>>16)&255)|(argb&65280)|((argb&255)<<16)|(argb&0xff000000); }
    private static boolean finite(float value) { return !Float.isNaN(value)&&!Float.isInfinite(value); }
    private static boolean unit(float value) { return finite(value)&&value>=0&&value<=1; }
    private static boolean normal(float value) { return finite(value)&&value>=-1&&value<=1; }
    public float x(){return x;} public float y(){return y;} public float z(){return z;}
    public float u(){return u;} public float v(){return v;}
    public float lightU(){return lightU;} public float lightV(){return lightV;}
    public float normalX(){return normalX;} public float normalY(){return normalY;} public float normalZ(){return normalZ;}
    /** RGBA8: red bits0..7, green8..15, blue16..23, alpha24..31. */
    public int rgba(){return rgba;}
}
