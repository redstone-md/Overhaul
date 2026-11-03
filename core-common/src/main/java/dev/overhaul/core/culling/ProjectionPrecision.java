package dev.overhaul.core.culling;

/** Conservative error budget for either float32 association of the pinned terrain shader's P * V * position. */
public final class ProjectionPrecision {
    private static final double UNIT=0x1.0p-24,GAMMA32=32*UNIT/(1-32*UNIT),MAX_INPUT=1_000_000;
    private final float[] projection,view;
    private final double[] combined=new double[16],input=new double[4],viewNorm=new double[4],viewInputError=new double[4];
    private final double[] clip=new double[4],clipError=new double[4],maxError=new double[4],maxRatio=new double[3];
    private final int width,height;
    private final ConservativeProjection.ClipDepth convention;
    private final boolean reversed;
    private final Thread thread=Thread.currentThread();
    private double minimumW,farthest,screenError,depthError,occluderDepth;

    /**
     * Inputs are the actual float matrices uploaded separately by the game. Position error
     * includes its two float additions and sub-block camera offset rounding. The caller
     * must keep section-minus-camera integer offsets exactly representable as float32;
     * normal visible terrain satisfies this bound. Relative inputs above one million fail open.
     */
    public ProjectionPrecision(float[] projection,float[] view,int width,int height,ConservativeProjection.ClipDepth convention,boolean reversed){
        if(projection==null||view==null||projection.length!=16||view.length!=16||width<1||height<1||convention==null)throw new IllegalArgumentException("Invalid precision parameters");
        this.projection=projection.clone();this.view=view.clone();this.width=width;this.height=height;this.convention=convention;this.reversed=reversed;
        for(int index=0;index<16;index++)if(!Float.isFinite(projection[index])||!Float.isFinite(view[index]))throw new IllegalArgumentException("Matrices must be finite");
        for(int col=0;col<4;col++)for(int row=0;row<4;row++){
            double norm=0;for(int k=0;k<4;k++){double term=(double)projection[k*4+row]*view[col*4+k];combined[col*4+row]+=term;norm+=Math.abs(term);}
            if(!Double.isFinite(norm)||norm>Float.MAX_VALUE/16)throw new IllegalArgumentException("Float matrix product may overflow");
        }
    }
    /** Rejects original near-plane crossings, poorly conditioned projection, and excessive float error. */
    public boolean safeQuad(double[] xyz,int offset){
        requireThread();if(xyz==null||offset<0||offset>xyz.length-12)throw new IllegalArgumentException("Four corners required");reset();
        for(int vertex=0;vertex<4;vertex++)if(!point(xyz[offset+vertex*3],xyz[offset+vertex*3+1],xyz[offset+vertex*3+2],true))return false;
        return finish(.002,1e-6)&&occluderDepth<1;
    }
    /** Successful bounds require one extra software cell of XY expansion and depthError() subtraction. */
    public boolean safeBounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        requireThread();reset();if(minX>maxX||minY>maxY||minZ>maxZ)return false;
        for(int corner=0;corner<8;corner++)if(!point((corner&1)==0?minX:maxX,(corner&2)==0?minY:maxY,(corner&4)==0?minZ:maxZ,false))return false;
        return finish(.25,1e-5);
    }
    public double screenError(){requireThread();return screenError;}
    public double depthError(){requireThread();return depthError;}
    /** Minimum safe occluder depth: farthest ORIGINAL corner plus the accepted roundoff bound. */
    public double occluderDepth(){requireThread();return occluderDepth;}
    private void reset(){
        minimumW=Double.POSITIVE_INFINITY;farthest=0;screenError=depthError=occluderDepth=Double.POSITIVE_INFINITY;
        for(int row=0;row<4;row++)maxError[row]=0;for(int row=0;row<3;row++)maxRatio[row]=0;
    }
    private boolean point(double x,double y,double z,boolean occluder){
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)))>MAX_INPUT)return false;
        input[0]=x;input[1]=y;input[2]=z;input[3]=1;
        for(int row=0;row<4;row++){
            double error=0,norm=0;
            for(int col=0;col<4;col++){
                double coefficient=view[col*4+row],positionError=col==3?0:8*UNIT*(Math.abs(input[col])+1)+1e-8;
                norm+=Math.abs(coefficient*input[col]);error+=Math.abs(coefficient)*positionError;
            }
            if(!Double.isFinite(norm+error)||norm+error>Float.MAX_VALUE/16)return false;
            viewNorm[row]=norm;viewInputError[row]=error;
        }
        for(int row=0;row<4;row++){
            double error=0,norm=0,reference=0;
            for(int col=0;col<4;col++){
                double coefficient=Math.abs(projection[col*4+row]);norm+=coefficient*viewNorm[col];
                error+=coefficient*viewInputError[col];reference+=combined[col*4+row]*input[col];
            }
            if(!Double.isFinite(norm+error)||norm+error>Float.MAX_VALUE/16||!Double.isFinite(reference))return false;
            // Expanded absolute terms retain cancellation hidden by V*position or by P*V.
            // The gamma term includes input uncertainty, including its second-order rounding.
            clip[row]=reference;clipError[row]=error+GAMMA32*(norm+error)+1e-13*(norm+error+1);
            maxError[row]=Math.max(maxError[row],clipError[row]);
        }
        double w=clip[3];if(!Double.isFinite(w)||w<=clipError[3])return false;minimumW=Math.min(minimumW,w);
        if(occluder){
            double near,uncertainty;
            if(reversed){near=w-clip[2];uncertainty=clipError[3]+clipError[2];}
            else if(convention==ConservativeProjection.ClipDepth.VULKAN_ZERO_TO_ONE){near=clip[2];uncertainty=clipError[2];}
            else{near=w+clip[2];uncertainty=clipError[3]+clipError[2];}
            if(!Double.isFinite(near)||near<=uncertainty)return false;
        }
        for(int row=0;row<3;row++)maxRatio[row]=Math.max(maxRatio[row],Math.abs(clip[row]/w));
        double depth=convention==ConservativeProjection.ClipDepth.VULKAN_ZERO_TO_ONE?clip[2]/w:(clip[2]/w+1)*.5;
        farthest=Math.max(farthest,reversed?1-depth:depth);return true;
    }
    private boolean finish(double pixelLimit,double depthLimit){
        double denominator=minimumW-maxError[3];if(denominator<=0)return false;
        double x=(maxError[0]+maxRatio[0]*maxError[3])/denominator,y=(maxError[1]+maxRatio[1]*maxError[3])/denominator;
        screenError=Math.max(width*.5*x,height*.5*y);
        depthError=(maxError[2]+maxRatio[2]*maxError[3])/denominator*(convention==ConservativeProjection.ClipDepth.OPENGL_NEGATIVE_ONE_TO_ONE?.5:1);
        occluderDepth=Math.max(0,Math.min(1,farthest+depthError));
        return Double.isFinite(screenError)&&Double.isFinite(depthError)&&screenError<pixelLimit&&depthError<depthLimit;
    }
    private void requireThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Precision guard belongs to a different thread");}
}
