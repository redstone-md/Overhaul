package dev.overhaul.core.culling;

import java.util.Arrays;

/**
 * Single-thread-owned, standard-Z software occlusion buffer. Input triangles must
 * already be clipped to the viewport and near plane. Only opaque occluders belong
 * here. Uncovered samples stay at far depth; partial coverage never hides a tile.
 */
public final class SoftwareDepthBuffer {
    private static final float DEPTH_BIAS = 0.00001f;
    private static final double COVERAGE_INSET = .01;
    private final int width, height;
    private final int[] widths, heights;
    private final float[][] levels;
    private final float[] triangle=new float[9];
    private final double[] edgeA=new double[16],edgeB=new double[16],edgeC=new double[16];
    // Level-zero cells changed since the hierarchy was last valid; empty when minimum exceeds maximum.
    private int dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY;

    public SoftwareDepthBuffer(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > 16_777_216) {
            throw new IllegalArgumentException("Invalid software depth dimensions");
        }
        this.width = width; this.height = height;
        int count = 1, w = width, h = height;
        while (w > 1 || h > 1) { w = (w + 1) / 2; h = (h + 1) / 2; count++; }
        widths = new int[count]; heights = new int[count]; levels = new float[count][];
        w = width; h = height;
        for (int level = 0; level < count; level++) {
            widths[level] = w; heights[level] = h; levels[level] = new float[w * h];
            w = (w + 1) / 2; h = (h + 1) / 2;
        }
        clear();
    }

    public void clear() {
        for (float[] level : levels) Arrays.fill(level, 1f);
        markClean();
    }

    private void markClean() {
        dirtyMinX = dirtyMinY = Integer.MAX_VALUE;
        dirtyMaxX = dirtyMaxY = 0;
    }

    public int width(){return width;}
    public int height(){return height;}

    /** Rasterizes only cells completely covered by this triangle, using their farthest depth. */
    public void rasterize(float ax, float ay, float az, float bx, float by, float bz,
                          float cx, float cy, float cz) {
        triangle[0]=ax;triangle[1]=ay;triangle[2]=az;triangle[3]=bx;triangle[4]=by;triangle[5]=bz;
        triangle[6]=cx;triangle[7]=cy;triangle[8]=cz;rasterizePolygon(triangle,3);
    }

    /**
     * A clipped convex planar polygon, packed xyz. Nonconvex/nonplanar/degenerate
     * geometry contributes nothing. Adjacent faces may be merged before this call
     * to avoid safe under-culling along triangle seams. Input is borrowed synchronously.
     */
    public void rasterizePolygon(float[] xyz,int vertices) {
        if(xyz==null||vertices<3||vertices>16||xyz.length<vertices*3)throw new IllegalArgumentException("Invalid occluder polygon");
        float left=width,top=height,right=0,bottom=0;
        for(int i=0;i<vertices;i++){
            int p=i*3;validate(xyz[p],xyz[p+1],xyz[p+2]);
            left=Math.min(left,xyz[p]);right=Math.max(right,xyz[p]);top=Math.min(top,xyz[p+1]);bottom=Math.max(bottom,xyz[p+1]);
        }
        double area=0;int third=0;
        for(int i=2;i<vertices;i++){
            area=edge(xyz[0],xyz[1],xyz[3],xyz[4],xyz[i*3],xyz[i*3+1]);
            if(Math.abs(area)>1e-10){third=i*3;break;}
        }
        if(third==0)return;
        double orientation=Math.signum(area);
        double dx=((xyz[5]-xyz[2])*(xyz[third+1]-xyz[1])-(xyz[third+2]-xyz[2])*(xyz[4]-xyz[1]))/area;
        double dy=((xyz[3]-xyz[0])*(xyz[third+2]-xyz[2])-(xyz[third]-xyz[0])*(xyz[5]-xyz[2]))/area;
        double base=xyz[2]-dx*xyz[0]-dy*xyz[1],excess=0;
        int edges=0;
        for(int i=0;i<vertices;i++){
            int p=i*3,q=(i+1)%vertices*3;
            double delta=xyz[p+2]-(base+dx*xyz[p]+dy*xyz[p+1]);
            if(Math.abs(delta)>1e-4)return;excess=Math.max(excess,delta);
            double ex=(double)xyz[q]-xyz[p],ey=(double)xyz[q+1]-xyz[p+1],length=Math.hypot(ex,ey);
            if(length<1e-10)continue;
            double a=-ey*orientation/length,b=ex*orientation/length,c=-a*xyz[p]-b*xyz[p+1];
            for(int j=0;j<vertices;j++)if(a*xyz[j*3]+b*xyz[j*3+1]+c < -1e-5)return;
            edgeA[edges]=a;edgeB[edges]=b;edgeC[edges++]=c;
        }
        if(edges<3)return;
        for(int y=Math.max(0,(int)Math.floor(top));y<Math.min(height,(int)Math.ceil(bottom));y++){
            for(int x=Math.max(0,(int)Math.floor(left));x<Math.min(width,(int)Math.ceil(right));x++){
                boolean full=true;
                for(int i=0;i<edges;i++){
                    double a=edgeA[i],b=edgeB[i];
                    if(a*(x+(a<0?1:0))+b*(y+(b<0?1:0))+edgeC[i]<=COVERAGE_INSET){full=false;break;}
                }
                if(!full)continue;
                double far=base+dx*(x+(dx>0?1:0))+dy*(y+(dy>0?1:0))+excess;
                float depth=Math.nextUp((float)Math.max(0,Math.min(1,far)));
                int index=y*width+x;
                if(depth<levels[0][index]){
                    levels[0][index]=depth;
                    dirtyMinX=Math.min(dirtyMinX,x);dirtyMaxX=Math.max(dirtyMaxX,x+1);
                    dirtyMinY=Math.min(dirtyMinY,y);dirtyMaxY=Math.max(dirtyMaxY,y+1);
                }
            }
        }
    }

    private void validate(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || x < 0 || y < 0 || x > width || y > height || z < 0 || z > 1) {
            throw new IllegalArgumentException("Triangles must be clipped; depth must be in [0,1]");
        }
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /**
     * Tests a conservative projected rectangle [min,max), and its NEAREST depth.
     * Bounds crossing the viewport/near plane must remain visible until clipped.
     */
    public boolean occluded(int minX, int minY, int maxX, int maxY, float nearestDepth) {
        if (minX < 0 || minY < 0 || maxX > width || maxY > height
                || minX >= maxX || minY >= maxY || !Float.isFinite(nearestDepth)
                || nearestDepth < 0 || nearestDepth > 1) return false;
        if (dirtyMaxX > 0) rebuild();
        return test(levels.length - 1, 0, 0, minX, minY, maxX, maxY, nearestDepth);
    }

    /** Occluders arrive between tests, so only the parents of changed cells are recomputed. */
    private void rebuild() {
        int minX = dirtyMinX, minY = dirtyMinY, maxX = dirtyMaxX, maxY = dirtyMaxY;
        for (int level = 1; level < levels.length; level++) {
            int childWidth = widths[level - 1], childHeight = heights[level - 1];
            minX >>= 1; minY >>= 1; maxX = (maxX + 1) >> 1; maxY = (maxY + 1) >> 1;
            for (int y = minY; y < maxY; y++) {
                for (int x = minX; x < maxX; x++) {
                    float maximum = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int cx = 2 * x + dx, cy = 2 * y + dy;
                            if (cx < childWidth && cy < childHeight) {
                                maximum = Math.max(maximum, levels[level - 1][cy * childWidth + cx]);
                            }
                        }
                    }
                    levels[level][y * widths[level] + x] = maximum;
                }
            }
        }
        markClean();
    }

    private boolean test(int level, int x, int y, int minX, int minY, int maxX, int maxY, float near) {
        int scale = 1 << level, left = x * scale, top = y * scale;
        int right = Math.min(width, left + scale), bottom = Math.min(height, top + scale);
        if (right <= minX || bottom <= minY || left >= maxX || top >= maxY) return true;
        if (near > levels[level][y * widths[level] + x] + DEPTH_BIAS) return true;
        if (level == 0 || (left >= minX && top >= minY && right <= maxX && bottom <= maxY)) return false;
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int cx = 2 * x + dx, cy = 2 * y + dy;
                if (cx < widths[level - 1] && cy < heights[level - 1]
                        && !test(level - 1, cx, cy, minX, minY, maxX, maxY, near)) return false;
            }
        }
        return true;
    }
}
