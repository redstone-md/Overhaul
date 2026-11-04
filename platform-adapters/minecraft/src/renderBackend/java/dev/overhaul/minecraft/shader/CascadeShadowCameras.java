package dev.overhaul.minecraft.shader;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** Camera-fitted practical splits; only light-space XY is cropped, preserving upstream casters in Z. */
public final class CascadeShadowCameras {
    private final int count,resolution;
    private final float lambda,blend;
    private final float[] splits,starts;
    private final float[][] projections,views,receivers;
    private boolean captured;
    public CascadeShadowCameras(int count,int resolution,float lambda,float blendFraction){
        if(count<2||count>4||resolution<64||resolution>4096||!Float.isFinite(lambda)||lambda<0||lambda>1
                ||!Float.isFinite(blendFraction)||blendFraction<0||blendFraction>0.25f)throw new IllegalArgumentException("Invalid cascade camera policy");
        this.count=count;this.resolution=resolution;this.lambda=lambda;blend=blendFraction;
        splits=new float[count];starts=new float[count];projections=new float[count][16];views=new float[count][16];receivers=new float[count][16];
    }
    public void capture(float[] projectionGL,float[] viewRotation,double cameraX,double cameraY,double cameraZ,float near,float far,float[] lightRotation,double casterRadius){
        matrix(projectionGL);matrix(viewRotation);matrix(lightRotation);
        if(!(near>0&&far>near)||!Float.isFinite(far)||!Double.isFinite(casterRadius)||casterRadius<=0
                ||!Double.isFinite(cameraX)||!Double.isFinite(cameraY)||!Double.isFinite(cameraZ))throw new IllegalArgumentException("Invalid cascade frame extent");
        Matrix4d inverseProjection=new Matrix4d().set(projectionGL).invert();
        Matrix4d inverseView=new Matrix4d().set(viewRotation).setTranslation(0,0,0).invert();
        Matrix4d light=new Matrix4d().set(lightRotation).setTranslation(0,0,0);
        Vector3d cameraLight=light.transformPosition(cameraX,cameraY,cameraZ,new Vector3d());
        for(int i=0;i<count;i++){
            double ratio=(i+1.0)/count;splits[i]=i==count-1?far:(float)(lambda*near*Math.pow(far/near,ratio)+(1-lambda)*(near+(far-near)*ratio));
            float previous=i==0?near:splits[i-1];starts[i]=i==count-1?far:splits[i]-(splits[i]-previous)*blend;
        }
        Vector3d[] corners=new Vector3d[8];for(int i=0;i<8;i++)corners[i]=new Vector3d();
        for(int cascade=0;cascade<count;cascade++){
            double first=cascade==0?near:starts[cascade-1],last=splits[cascade];Vector3d center=new Vector3d();
            for(int corner=0;corner<8;corner++){
                double x=(corner&1)==0?-1:1,y=(corner&2)==0?-1:1,depth=(corner&4)==0?first:last;
                Vector4d a=inverseProjection.transform(x,y,-1,1,new Vector4d());
                Vector4d b=inverseProjection.transform(x,y,0,1,new Vector4d());
                if(Math.abs(a.w)<1e-12||Math.abs(b.w)<1e-12)throw new IllegalArgumentException("Singular cascade camera ray");
                a.div(a.w);b.div(b.w);double dz=b.z-a.z;
                if(Math.abs(dz)<1e-12)throw new IllegalArgumentException("Cascade view ray does not cross depth planes");
                double t=(-depth-a.z)/dz;
                inverseView.transformPosition(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,-depth,corners[corner]);
                center.add(corners[corner]);
            }
            center.div(8);double radius=0;
            for(Vector3d corner:corners)radius=Math.max(radius,corner.distance(center));
            // Quantized sphere extents avoid camera-rotation-dependent footprint breathing.
            radius=Math.ceil(radius*16)/16;radius*=resolution/(resolution-4.0);
            Vector3d centerLight=light.transformPosition(center,new Vector3d());double texel=2*radius/resolution;
            double sx=Math.floor((cameraLight.x+centerLight.x)/texel+0.5)*texel-cameraLight.x;
            double sy=Math.floor((cameraLight.y+centerLight.y)/texel+0.5)*texel-cameraLight.y;
            Matrix4d view=new Matrix4d(light).setTranslation(-sx,-sy,0);
            double z=Math.max(casterRadius,far+radius)+16;
            Matrix4d projection=new Matrix4d().setOrtho(-radius,radius,-radius,radius,-z,z,false);
            view.get(views[cascade]);projection.get(projections[cascade]);projection.mul(view).get(receivers[cascade]);
            matrix(views[cascade]);matrix(projections[cascade]);matrix(receivers[cascade]);
        }
        captured=true;
    }
    public int count(){return count;}
    public float splitDepth(int cascade){require(cascade);return splits[cascade];}
    public float blendStart(int cascade){require(cascade);return starts[cascade];}
    public float[] projection(int cascade){require(cascade);return projections[cascade].clone();}
    public float[] view(int cascade){require(cascade);return views[cascade].clone();}
    public float[] receiverMatrix(int cascade){require(cascade);return receivers[cascade].clone();}
    private void require(int cascade){if(!captured||cascade<0||cascade>=count)throw new IllegalStateException("Cascade camera is not captured");}
    private static void matrix(float[] matrix){if(matrix.length!=16)throw new IllegalArgumentException("Expected mat4");for(float v:matrix)if(!Float.isFinite(v))throw new IllegalArgumentException("Nonfinite cascade matrix");}
}
