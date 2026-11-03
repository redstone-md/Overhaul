package dev.overhaul.minecraft.model;

import net.minecraft.client.model.geom.ModelPart;

/** Immutable exact box-corner encoding of one original model polygon, including its vertex order. */
public final class ModelQuadGeometry {
    private final float[] values;
    private final int cornerMask;

    private ModelQuadGeometry(float[] values,int cornerMask){this.values=values;this.cornerMask=cornerMask;}

    public static ModelQuadGeometry create(ModelPart.Polygon polygon){
        if(polygon==null||polygon.vertices().length!=4)return null;
        var vertices=polygon.vertices();var values=new float[17];int mask=0;
        for(int axis=0;axis<3;axis++){
            float minimum=Float.POSITIVE_INFINITY,maximum=Float.NEGATIVE_INFINITY;
            for(var vertex:vertices){
                float coordinate=coordinate(vertex,axis);if(!Float.isFinite(coordinate))return null;
                minimum=Math.min(minimum,coordinate);maximum=Math.max(maximum,coordinate);
            }
            values[axis]=minimum;values[axis+3]=maximum;
            for(int vertex=0;vertex<4;vertex++){
                int bits=Float.floatToRawIntBits(coordinate(vertices[vertex],axis));
                if(bits==Float.floatToRawIntBits(minimum))continue;
                if(bits!=Float.floatToRawIntBits(maximum))return null;
                mask|=1<<(vertex*3+axis);
            }
        }
        for(int vertex=0;vertex<4;vertex++){
            values[6+vertex*2]=vertices[vertex].u();values[7+vertex*2]=vertices[vertex].v();
        }
        values[14]=polygon.normal().x();values[15]=polygon.normal().y();values[16]=polygon.normal().z();
        return new ModelQuadGeometry(values,mask);
    }

    private static float coordinate(ModelPart.Vertex vertex,int axis){
        return switch(axis){case 0->vertex.worldX();case 1->vertex.worldY();case 2->vertex.worldZ();default->throw new IndexOutOfBoundsException(axis);};
    }
    public float min(int axis){checkAxis(axis);return values[axis];}
    public float max(int axis){checkAxis(axis);return values[axis+3];}
    public float coordinate(int vertex,int axis){
        checkVertex(vertex);checkAxis(axis);return values[axis+(((cornerMask>>>(vertex*3+axis))&1)*3)];
    }
    public float u(int vertex){checkVertex(vertex);return values[6+vertex*2];}
    public float v(int vertex){checkVertex(vertex);return values[7+vertex*2];}
    public float normalX(){return values[14];}
    public float normalY(){return values[15];}
    public float normalZ(){return values[16];}
    public int cornerMask(){return cornerMask;}
    private static void checkAxis(int axis){if(axis<0||axis>2)throw new IndexOutOfBoundsException(axis);}
    private static void checkVertex(int vertex){if(vertex<0||vertex>3)throw new IndexOutOfBoundsException(vertex);}
}
