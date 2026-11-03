package dev.overhaul.core.model;

/** Four source vertices retain their baked winding, UV seams, tint and individual light samples. */
public final class ModelQuad {
    private final RenderLayer layer;
    private final int materialId;
    private final float alphaCutoff;
    private final ModelVertex[] vertices;

    public ModelQuad(RenderLayer layer,int materialId,float alphaCutoff,ModelVertex... vertices) {
        if (layer==null || materialId<0 || Float.isNaN(alphaCutoff)||alphaCutoff<0||alphaCutoff>1 || vertices.length!=4) throw new IllegalArgumentException("Invalid baked quad");
        this.vertices=vertices.clone();
        for (ModelVertex vertex:this.vertices) if (vertex==null) throw new NullPointerException("quad vertex");
        this.layer=layer; this.materialId=materialId; this.alphaCutoff=alphaCutoff;
    }
    public RenderLayer layer(){return layer;}
    public int materialId(){return materialId;}
    public float alphaCutoff(){return alphaCutoff;}
    public ModelVertex vertex(int index){return vertices[index];}
    double centerX(){return ((double)vertices[0].x()+vertices[1].x()+vertices[2].x()+vertices[3].x())*.25;}
    double centerY(){return ((double)vertices[0].y()+vertices[1].y()+vertices[2].y()+vertices[3].y())*.25;}
    double centerZ(){return ((double)vertices[0].z()+vertices[1].z()+vertices[2].z()+vertices[3].z())*.25;}
}
