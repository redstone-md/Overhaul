package dev.overhaul.minecraft.model;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class ModelQuadGeometryTest {
    @Test void actualCubePolygonsRetainEveryOrderedRawCoordinateUvAndNormalAcrossMirroring(){
        for(boolean mirrored:new boolean[]{false,true}){
            var cube=new ModelPart.Cube(3,7,-7.3137f,-5.7819f,-2.4431f,13.637f,11.591f,8.237f,
                    .237f,-.137f,.091f,mirrored,64,32,EnumSet.allOf(Direction.class));
            assertEquals(6,cube.polygons.length);
            for(var polygon:cube.polygons){
                ModelQuadGeometry geometry=ModelQuadGeometry.create(polygon);assertNotNull(geometry);
                for(int vertex=0;vertex<4;vertex++){
                    var original=polygon.vertices()[vertex];
                    raw(original.worldX(),geometry.coordinate(vertex,0));
                    raw(original.worldY(),geometry.coordinate(vertex,1));
                    raw(original.worldZ(),geometry.coordinate(vertex,2));
                    raw(original.u(),geometry.u(vertex));raw(original.v(),geometry.v(vertex));
                }
                raw(polygon.normal().x(),geometry.normalX());raw(polygon.normal().y(),geometry.normalY());raw(polygon.normal().z(),geometry.normalZ());
                assertEquals(0,geometry.cornerMask()&~0xFFF);
            }
        }
    }

    @Test void geometryOwnsItsValuesAndPreservesSignedZeroCorners(){
        var vertices=new ModelPart.Vertex[]{vertex(-0f,-8,0,.13f,.07f),vertex(+0f,-8,0,.83f,.19f),
                vertex(+0f,8,0,.74f,.93f),vertex(-0f,8,0,.09f,.72f)};
        Vector3f normal=new Vector3f(.21731f,-.61371f,.94319f);
        var geometry=ModelQuadGeometry.create(new ModelPart.Polygon(vertices,normal));assertNotNull(geometry);
        raw(-0f,geometry.coordinate(0,0));raw(+0f,geometry.coordinate(1,0));
        raw(+0f,geometry.coordinate(2,0));raw(-0f,geometry.coordinate(3,0));
        float original=geometry.u(0);vertices[0]=vertex(99,99,99,99,99);normal.set(1,2,3);
        raw(original,geometry.u(0));raw(.21731f,geometry.normalX());raw(-.61371f,geometry.normalY());raw(.94319f,geometry.normalZ());
        assertThrows(IndexOutOfBoundsException.class,()->geometry.coordinate(4,0));
        assertThrows(IndexOutOfBoundsException.class,()->geometry.min(-1));
    }

    @Test void unsupportedNonCornerAndNonfiniteGeometryIsRejected(){
        assertNull(ModelQuadGeometry.create(null));
        assertNull(ModelQuadGeometry.create(new ModelPart.Polygon(new ModelPart.Vertex[0],new Vector3f(0,0,1))));
        var vertices=new ModelPart.Vertex[]{vertex(0,0,0,0,0),vertex(8,0,0,1,0),vertex(16,16,0,1,1),vertex(0,16,0,0,1)};
        assertNull(ModelQuadGeometry.create(new ModelPart.Polygon(vertices,new Vector3f(0,0,1))));
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
            vertices[1]=vertex(invalid,0,0,1,0);
            assertNull(ModelQuadGeometry.create(new ModelPart.Polygon(vertices,new Vector3f(0,0,1))));
        }
    }

    private static ModelPart.Vertex vertex(float x,float y,float z,float u,float v){return new ModelPart.Vertex(x,y,z,u,v);}
    private static void raw(float expected,float actual){assertEquals(Float.floatToRawIntBits(expected),Float.floatToRawIntBits(actual));}
}
