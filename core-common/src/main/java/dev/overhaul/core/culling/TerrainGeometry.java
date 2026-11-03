package dev.overhaul.core.culling;

/** Immutable local bounds and certified outward unit-face unions owned by one compiled mesh. */
public final class TerrainGeometry {
    private final double[] bounds;
    private final byte[] faces;
    TerrainGeometry(double[] bounds,byte[] faces) { this.bounds=bounds.clone();this.faces=faces.clone(); }
    public boolean hasBounds() { return Double.isFinite(bounds[0]); }
    public double min(int axis) { return bounds[axis]; }
    public double max(int axis) { return bounds[axis+3]; }
    public int faceCount() { return faces.length/6; }
    public int faceAxis(int face) { return unsigned(face,0)/2; }
    public int faceSign(int face) { return unsigned(face,0)%2==0?1:-1; }
    public int facePlane(int face) { return unsigned(face,1); }
    /** Four convex corners with the certified face's original outward winding. */
    public void face(int face,double originX,double originY,double originZ,double[] output) {
        if(output.length<12)throw new IllegalArgumentException("Four corners required");
        int axis=faceAxis(face),u=(axis+1)%3,v=(axis+2)%3;
        for(int vertex=0;vertex<4;vertex++) {
            int corner=faceSign(face)>0?vertex:(4-vertex)%4;
            output[vertex*3+axis]=component(axis,originX,originY,originZ)+facePlane(face);
            output[vertex*3+u]=component(u,originX,originY,originZ)+unsigned(face,corner==1||corner==2?4:2);
            output[vertex*3+v]=component(v,originX,originY,originZ)+unsigned(face,corner>=2?5:3);
        }
    }
    private static double component(int axis,double x,double y,double z){return axis==0?x:axis==1?y:z;}
    private int unsigned(int face,int field) { return faces[face*6+field]&255; }
}
