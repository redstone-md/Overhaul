package dev.overhaul.core.culling;

/** Thread-confined projection of camera-relative geometry into standard-Z software depth. */
public final class ConservativeProjection {
    public enum ClipDepth { OPENGL_NEGATIVE_ONE_TO_ONE, VULKAN_ZERO_TO_ONE }
    public static final class ProjectedBounds {
        private final int minX,minY,maxX,maxY;
        private final float nearestDepth;
        private ProjectedBounds(int minX,int minY,int maxX,int maxY,float nearestDepth){this.minX=minX;this.minY=minY;this.maxX=maxX;this.maxY=maxY;this.nearestDepth=nearestDepth;}
        public int minX(){return minX;}public int minY(){return minY;}public int maxX(){return maxX;}public int maxY(){return maxY;}
        public float nearestDepth(){return nearestDepth;}
    }
    private static final int MAX_VERTICES=16;
    private final double[] matrix;
    private final int width,height;
    private final ClipDepth depth;
    private final boolean reversedDepth;
    private final Thread thread=Thread.currentThread();
    private final double[] first=new double[MAX_VERTICES*4],second=new double[MAX_VERTICES*4],screen=new double[MAX_VERTICES*3];
    private final float[] polygon=new float[MAX_VERTICES*3];

    /** Matrix is FINAL projection * view rotation, column-major; translation belongs in double input subtraction. */
    public ConservativeProjection(double[] matrix,int width,int height,ClipDepth depth,boolean reversedDepth){
        if(matrix==null||matrix.length!=16||depth==null||width<1||height<1||(long)width*height>16_777_216)throw new IllegalArgumentException("Invalid projection parameters");
        this.matrix=matrix.clone();this.width=width;this.height=height;this.depth=depth;this.reversedDepth=reversedDepth;
        validateMatrix(this.matrix);
    }
    /** An uncertain/crossing/behind-camera box returns null and must remain visible. */
    public ProjectedBounds projectBounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
        requireThread();if(!finite(minX,minY,minZ,maxX,maxY,maxZ)||minX>maxX||minY>maxY||minZ>maxZ)return null;
        double left=Double.POSITIVE_INFINITY,top=Double.POSITIVE_INFINITY,right=Double.NEGATIVE_INFINITY,bottom=Double.NEGATIVE_INFINITY,near=1;
        for(int corner=0;corner<8;corner++){
            transform((corner&1)==0?minX:maxX,(corner&2)==0?minY:maxY,(corner&4)==0?minZ:maxZ,first,0);
            double w=first[3];if(!finite(first[0],first[1],first[2],w)||w<=positiveW(first,4))return null;
            for(int plane=0;plane<6;plane++)if(distance(first,0,plane,0)<0)return null;
            double x=(first[0]/w+1)*.5*width,y=(first[1]/w+1)*.5*height,z=standardDepth(first[2]/w);
            left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);near=Math.min(near,z);
        }
        if(!finite(left,top,right,bottom,near)||near<0||near>1)return null;
        int x0=Math.max(0,(int)Math.floor(left-1e-7)),y0=Math.max(0,(int)Math.floor(top-1e-7));
        int x1=Math.min(width,(int)Math.ceil(right+1e-7)),y1=Math.min(height,(int)Math.ceil(bottom+1e-7));
        if(x0>=x1||y0>=y1)return null;
        return new ProjectedBounds(x0,y0,x1,y1,Math.max(0,Math.nextDown((float)near)));
    }
    public boolean rasterizeQuad(SoftwareDepthBuffer target,double[] xyz){return rasterizeQuad(target,xyz,0);}
    /** Only planar convex quads qualify. No face objects, arrays, or buffers are allocated here. */
    public boolean rasterizeQuad(SoftwareDepthBuffer target,double[] xyz,int offset){
        return rasterizeQuad(target,xyz,offset,0);
    }
    /** An external precision guard may supply a farther lower bound for the stored occluder depth. */
    public boolean rasterizeQuad(SoftwareDepthBuffer target,double[] xyz,int offset,double minimumOccluderDepth){
        requireThread();if(target==null||xyz==null||offset<0||offset>xyz.length-12)throw new IllegalArgumentException("Four XYZ corners are required");
        if(!Double.isFinite(minimumOccluderDepth)||minimumOccluderDepth<0||minimumOccluderDepth>1)throw new IllegalArgumentException("Occluder depth must be in [0,1]");
        if(target.width()!=width||target.height()!=height)throw new IllegalArgumentException("Projection and depth dimensions differ");
        if(!validQuad(xyz,offset))return false;
        for(int vertex=0;vertex<4;vertex++)transform(xyz[offset+vertex*3],xyz[offset+vertex*3+1],xyz[offset+vertex*3+2],first,vertex*4);
        for(int component=0;component<16;component++)if(!Double.isFinite(first[component]))return false;
        double wGuard=positiveW(first,16);double[] input=first,output=second;int count=4;
        for(int plane=0;plane<7;plane++){
            count=clip(input,output,count,plane,wGuard);if(count<3)return false;
            double[] swap=input;input=output;output=swap;
        }
        double centerX=0,centerY=0,farthest=minimumOccluderDepth;
        for(int vertex=0;vertex<count;vertex++){
            int clip=vertex*4,projected=vertex*3;double w=input[clip+3];
            if(w<wGuard*.5)return false;
            double x=(input[clip]/w+1)*.5*width,y=(input[clip+1]/w+1)*.5*height,z=standardDepth(input[clip+2]/w);
            if(!finite(x,y,z)||z< -1e-9||z>1+1e-9)return false;
            screen[projected]=Math.max(0,Math.min(width,x));screen[projected+1]=Math.max(0,Math.min(height,y));screen[projected+2]=Math.max(0,Math.min(1,z));
            centerX+=screen[projected];centerY+=screen[projected+1];farthest=Math.max(farthest,screen[projected+2]);
        }
        centerX/=count;centerY/=count;double area=area(screen,count);if(!Double.isFinite(area)||Math.abs(area)<1e-10)return false;
        float far=Math.min(1,Math.nextUp((float)farthest));
        // Slightly contract before float conversion, then verify every converted point is inside the exact polygon.
        // Using its global farthest depth cannot invent occlusion on a tilted face or from depth rounding.
        for(int vertex=0;vertex<count;vertex++){
            int position=vertex*3;polygon[position]=(float)(centerX+(screen[position]-centerX)*(1-1e-6));
            polygon[position+1]=(float)(centerY+(screen[position+1]-centerY)*(1-1e-6));polygon[position+2]=far;
        }
        for(int edge=0;edge<count;edge++)for(int vertex=0;vertex<count;vertex++){
            int a=edge*3,b=(edge+1)%count*3,p=vertex*3;
            double side=(screen[b]-screen[a])*(polygon[p+1]-screen[a+1])-(screen[b+1]-screen[a+1])*(polygon[p]-screen[a]);
            if(side*Math.signum(area)<0)return false;
        }
        target.rasterizePolygon(polygon,count);return true;
    }
    private int clip(double[] input,double[] output,int count,int plane,double wGuard){
        int written=0,previous=(count-1)*4;double before=distance(input,previous,plane,wGuard);
        for(int vertex=0;vertex<count;vertex++){
            int current=vertex*4;double after=distance(input,current,plane,wGuard);boolean a=before>=0,b=after>=0;
            if(a!=b){
                if(written==MAX_VERTICES)return 0;double fraction=before/(before-after);if(!Double.isFinite(fraction)||fraction<0||fraction>1)return 0;
                for(int component=0;component<4;component++)output[written*4+component]=input[previous+component]+fraction*(input[current+component]-input[previous+component]);
                if(written==0||!sameVertex(output,(written-1)*4,written*4))written++;
            }
            if(b){if(written==MAX_VERTICES)return 0;System.arraycopy(input,current,output,written*4,4);if(written==0||!sameVertex(output,(written-1)*4,written*4))written++;}
            previous=current;before=after;
        }
        if(written>1&&sameVertex(output,0,(written-1)*4))written--;return written;
    }
    private double distance(double[] points,int offset,int plane,double wGuard){
        double x=points[offset],y=points[offset+1],z=points[offset+2],w=points[offset+3];
        switch(plane){case 0:return w+x;case 1:return w-x;case 2:return w+y;case 3:return w-y;case 4:return depth==ClipDepth.VULKAN_ZERO_TO_ONE?z:w+z;case 5:return w-z;default:return w-wGuard;}
    }
    private double standardDepth(double z){double standard=depth==ClipDepth.OPENGL_NEGATIVE_ONE_TO_ONE?(z+1)*.5:z;return reversedDepth?1-standard:standard;}
    private void transform(double x,double y,double z,double[] destination,int offset){for(int row=0;row<4;row++)destination[offset+row]=matrix[row]*x+matrix[4+row]*y+matrix[8+row]*z+matrix[12+row];}
    private static double positiveW(double[] values,int length){double scale=1;for(int index=0;index<length;index++)scale=Math.max(scale,Math.abs(values[index]));return scale*1e-12;}
    private static double area(double[] points,int count){double result=0;for(int vertex=0;vertex<count;vertex++){int a=vertex*3,b=(vertex+1)%count*3;result+=points[a]*points[b+1]-points[b]*points[a+1];}return result;}
    private static boolean validQuad(double[] xyz,int offset){
        for(int index=0;index<12;index++)if(!Double.isFinite(xyz[offset+index]))return false;
        double ax=xyz[offset+3]-xyz[offset],ay=xyz[offset+4]-xyz[offset+1],az=xyz[offset+5]-xyz[offset+2];
        double bx=xyz[offset+6]-xyz[offset],by=xyz[offset+7]-xyz[offset+1],bz=xyz[offset+8]-xyz[offset+2];
        double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,norm=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(!Double.isFinite(norm)||norm<1e-12)return false;
        double scale=Math.max(1,Math.max(Math.sqrt(ax*ax+ay*ay+az*az),Math.sqrt(bx*bx+by*by+bz*bz)));
        double plane=nx*(xyz[offset+9]-xyz[offset])+ny*(xyz[offset+10]-xyz[offset+1])+nz*(xyz[offset+11]-xyz[offset+2]);
        if(!Double.isFinite(plane)||Math.abs(plane)>norm*scale*1e-10)return false;
        for(int vertex=0;vertex<4;vertex++){
            int a=offset+vertex*3,b=offset+(vertex+1)%4*3,c=offset+(vertex+2)%4*3;
            double ux=xyz[b]-xyz[a],uy=xyz[b+1]-xyz[a+1],uz=xyz[b+2]-xyz[a+2],vx=xyz[c]-xyz[b],vy=xyz[c+1]-xyz[b+1],vz=xyz[c+2]-xyz[b+2];
            double turn=(uy*vz-uz*vy)*nx+(uz*vx-ux*vz)*ny+(ux*vy-uy*vx)*nz;
            if(!Double.isFinite(turn)||turn<=norm*norm*1e-12)return false;
        }
        return true;
    }
    private static void validateMatrix(double[] matrix){
        double scale=0;for(double value:matrix){if(!Double.isFinite(value))throw new IllegalArgumentException("Projection matrix must be finite");scale=Math.max(scale,Math.abs(value));}
        if(scale==0)throw new IllegalArgumentException("Projection matrix is singular");
        double[] rows=new double[16];for(int row=0;row<4;row++)for(int col=0;col<4;col++)rows[row*4+col]=matrix[col*4+row]/scale;
        for(int col=0;col<4;col++){
            int pivot=col;for(int row=col+1;row<4;row++)if(Math.abs(rows[row*4+col])>Math.abs(rows[pivot*4+col]))pivot=row;
            if(Math.abs(rows[pivot*4+col])<1e-14)throw new IllegalArgumentException("Projection matrix is singular or numerically uncertain");
            for(int index=col;index<4;index++){double swap=rows[col*4+index];rows[col*4+index]=rows[pivot*4+index];rows[pivot*4+index]=swap;}
            for(int row=col+1;row<4;row++){double factor=rows[row*4+col]/rows[col*4+col];for(int index=col;index<4;index++)rows[row*4+index]-=factor*rows[col*4+index];}
        }
    }
    private static boolean sameVertex(double[] vertices,int a,int b){return vertices[a]==vertices[b]&&vertices[a+1]==vertices[b+1]&&vertices[a+2]==vertices[b+2]&&vertices[a+3]==vertices[b+3];}
    private static boolean finite(double a,double b,double c){return Double.isFinite(a)&&Double.isFinite(b)&&Double.isFinite(c);}
    private static boolean finite(double a,double b,double c,double d){return finite(a,b,c)&&Double.isFinite(d);}
    private static boolean finite(double a,double b,double c,double d,double e){return finite(a,b,c,d)&&Double.isFinite(e);}
    private static boolean finite(double a,double b,double c,double d,double e,double f){return finite(a,b,c,d,e)&&Double.isFinite(f);}
    private void requireThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Projection belongs to a different thread");}
}
