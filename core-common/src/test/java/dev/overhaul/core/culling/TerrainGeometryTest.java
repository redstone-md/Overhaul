package dev.overhaul.core.culling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainGeometryTest {
    @Test void mergesCertifiedFacesWithoutFillingAnOpeningAndKeepsAllEmittedBounds() {
        TerrainGeometryBuilder builder=new TerrainGeometryBuilder();
        for(int v=0;v<4;v++)for(int u=0;u<4;u++)if(u!=1||v!=1)emit(builder,quad(0,2,u,v,false));
        builder.vertex(-3.25f,22.5f,1.5f); // Non-occluding custom-model protrusion still enlarges candidate bounds.
        TerrainGeometry geometry=builder.build();
        assertEquals(-3.25,geometry.min(0));assertEquals(22.5,geometry.max(1));
        assertTrue(geometry.faceCount()<15);
        int[][] coverage=new int[4][4];double[] face=new double[12];
        for(int i=0;i<geometry.faceCount();i++) {
            assertEquals(0,geometry.faceAxis(i));assertEquals(1,geometry.faceSign(i));
            geometry.face(i,0,0,0,face);
            int minU=16,minV=16,maxU=0,maxV=0;
            for(int vertex=0;vertex<4;vertex++){assertEquals(2,face[vertex*3]);minU=Math.min(minU,(int)face[vertex*3+1]);maxU=Math.max(maxU,(int)face[vertex*3+1]);minV=Math.min(minV,(int)face[vertex*3+2]);maxV=Math.max(maxV,(int)face[vertex*3+2]);}
            for(int v=minV;v<maxV;v++)for(int u=minU;u<maxU;u++)coverage[v][u]++;
        }
        for(int v=0;v<4;v++)for(int u=0;u<4;u++)assertEquals(u==1&&v==1?0:1,coverage[v][u]);
    }
    @Test void rejectsPartialNonGridBowTieAndOutOfSectionFacesAndRetainsBothWindings() {
        TerrainGeometryBuilder builder=new TerrainGeometryBuilder();
        float[] partial=quad(2,1,0,0,false);partial[3]=.5f;
        assertFalse(builder.solidQuad(partial));
        float[] bow=quad(2,1,0,0,false);
        for(int axis=0;axis<3;axis++){float swap=bow[3+axis];bow[3+axis]=bow[6+axis];bow[6+axis]=swap;}
        assertFalse(builder.solidQuad(bow));assertFalse(builder.solidQuad(quad(1,17,0,0,false)));
        emit(builder,quad(1,3,0,0,false));emit(builder,quad(1,3,0,0,true));
        TerrainGeometry result=builder.build();assertEquals(2,result.faceCount());
        assertNotEquals(result.faceSign(0),result.faceSign(1));
        double[] face=new double[12];result.face(0,100,200,300,face);
        for(int vertex=0;vertex<4;vertex++)assertEquals(203,face[vertex*3+1]);
        assertThrows(IllegalStateException.class,builder::build);
    }
    @Test void capsCheckerboardMetadataWithoutInventingFaces() {
        TerrainGeometryBuilder builder=new TerrainGeometryBuilder();
        for(int axis=0;axis<3;axis++)for(int plane=0;plane<=16;plane++)for(int v=0;v<16;v++)for(int u=0;u<16;u++)
            if((u+v)%2==0){emit(builder,quad(axis,plane,u,v,false));emit(builder,quad(axis,plane,u,v,true));}
        TerrainGeometry result=builder.build();assertEquals(4096,result.faceCount());
        double[] face=new double[12];
        for(int i=0;i<result.faceCount();i++) {
            result.face(i,0,0,0,face);int axis=result.faceAxis(i),u=(axis+1)%3,v=(axis+2)%3;
            int minU=16,minV=16,maxU=0,maxV=0;
            for(int vertex=0;vertex<4;vertex++){minU=Math.min(minU,(int)face[vertex*3+u]);maxU=Math.max(maxU,(int)face[vertex*3+u]);minV=Math.min(minV,(int)face[vertex*3+v]);maxV=Math.max(maxV,(int)face[vertex*3+v]);}
            assertEquals(1,maxU-minU);assertEquals(1,maxV-minV);assertEquals(0,(minU+minV)%2);
        }
    }
    @Test void nonFiniteOrEmptyBoundsNeverBecomeCullable() {
        assertFalse(new TerrainGeometryBuilder().build().hasBounds());
        TerrainGeometryBuilder builder=new TerrainGeometryBuilder();emit(builder,quad(0,0,0,0,false));builder.vertex(Float.NaN,0,0);
        assertFalse(builder.build().hasBounds());
    }
    private static void emit(TerrainGeometryBuilder builder,float[] quad) {
        for(int vertex=0;vertex<4;vertex++)builder.vertex(quad[vertex*3],quad[vertex*3+1],quad[vertex*3+2]);
        assertTrue(builder.solidQuad(quad));
    }
    private static float[] quad(int axis,int plane,int u,int v,boolean reverse) {
        float[] output=new float[12];int first=(axis+1)%3,second=(axis+2)%3;
        for(int i=0;i<4;i++){int corner=reverse?(4-i)%4:i;output[i*3+axis]=plane;output[i*3+first]=u+(corner==1||corner==2?1:0);output[i*3+second]=v+(corner>=2?1:0);}
        return output;
    }
}
