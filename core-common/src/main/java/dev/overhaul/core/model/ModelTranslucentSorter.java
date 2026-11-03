package dev.overhaul.core.model;

import java.nio.ByteBuffer;

/**
 * Thread-confined stable centroid-distance ordering. Intersecting/very large quads
 * may require polygon splitting or OIT; this policy deliberately does neither.
 * Scratch storage is reused and only indices change when the camera moves.
 */
public final class ModelTranslucentSorter {
    private final double[] centers;
    private final int[] vertexBases,order,scratch;
    private final double[] distances;

    ModelTranslucentSorter(double[] centers,int[] vertexBases) {
        this.centers=centers.clone(); this.vertexBases=vertexBases.clone();
        order=new int[vertexBases.length]; scratch=new int[vertexBases.length]; distances=new double[vertexBases.length];
    }
    public int indexCount(){return vertexBases.length*6;}

    public void writeSortedIndices(ByteBuffer destination,double cameraX,double cameraY,double cameraZ) {
        if (!Double.isFinite(cameraX)||!Double.isFinite(cameraY)||!Double.isFinite(cameraZ)) throw new IllegalArgumentException("Nonfinite camera");
        if (destination.remaining()<indexCount()*4) throw new IllegalArgumentException("Translucent index destination too small");
        for (int index=0;index<order.length;index++) {
            order[index]=index;
            double dx=centers[index*3]-cameraX,dy=centers[index*3+1]-cameraY,dz=centers[index*3+2]-cameraZ;
            distances[index]=Math.hypot(Math.hypot(dx,dy),dz);
        }
        merge(0,order.length);
        for (int index:order) ModelMesher.writeQuadIndices(destination,vertexBases[index]);
    }
    private void merge(int start,int end) {
        if (end-start<2) return;
        int middle=(start+end)>>>1; merge(start,middle); merge(middle,end);
        int left=start,right=middle;
        for (int output=start;output<end;output++) {
            if (right>=end || (left<middle && distances[order[left]]>=distances[order[right]])) scratch[output]=order[left++];
            else scratch[output]=order[right++];
        }
        System.arraycopy(scratch,start,order,start,end-start);
    }
}
