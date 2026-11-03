package dev.overhaul.minecraft.model;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class ModelGeometryCacheTest {
    @Test void refreshesPolygonVertexAndMutableNormalValuesWithoutAccumulatingOldQuadBudget(){
        ModelPart.Cube cube=cube();ModelGeometryCache cache=new ModelGeometryCache();
        List<ModelQuadGeometry> original=cache.quads(cube);assertSame(original,cache.quads(cube));assertEquals(1,cache.retainedQuads());
        ModelPart.Polygon polygon=cube.polygons[0];ModelPart.Vertex[] vertices=polygon.vertices().clone();
        vertices[0]=vertices[0].remap(.137f,.913f);Vector3f normal=new Vector3f(.25f,.5f,-.75f);
        cube.polygons[0]=new ModelPart.Polygon(vertices,normal);
        List<ModelQuadGeometry> replaced=cache.quads(cube);assertNotSame(original,replaced);assertEquals(.137f,replaced.getFirst().u(0));
        assertEquals(.25f,replaced.getFirst().normalX());assertEquals(1,cache.retainedQuads());assertEquals(1,cache.cubes());
        vertices[1]=vertices[1].remap(-.23f,.817f);List<ModelQuadGeometry> remapped=cache.quads(cube);
        assertNotSame(replaced,remapped);assertEquals(-.23f,remapped.getFirst().u(1));assertEquals(1,cache.retainedQuads());
        normal.set(-0f,.375f,.875f);ModelQuadGeometry changed=cache.quads(cube).getFirst();
        assertEquals(Float.floatToRawIntBits(-0f),Float.floatToRawIntBits(changed.normalX()));assertEquals(.375f,changed.normalY());
        assertEquals(.25f,replaced.getFirst().normalX(),"Previously captured immutable metadata must retain its own values");
    }
    @Test void unsupportedMutationCanRecoverAndDefinitionsRefreshBeforeAnyAnimation(){
        Fixture fixture=new Fixture();Part part=fixture.part(cube());CountingModel model=new CountingModel(part.part);
        ModelGeometryCache cache=fixture.cache(4);assertEquals(new ModelGeometryCache.Definition(true,1),cache.definition(model));
        ModelPart.Cube cube=part.cubes.getFirst();ModelPart.Polygon original=cube.polygons[0];ModelPart.Vertex[] bad=original.vertices().clone();
        bad[0]=new ModelPart.Vertex(Float.NaN,0,0,0,0);cube.polygons[0]=new ModelPart.Polygon(bad,original.normal());
        assertFalse(cache.definition(model).compatible());assertEquals(0,cache.retainedQuads());assertEquals(0,cache.cubes());
        assertThrows(IllegalStateException.class,()->cache.quads(cube));
        cube.polygons[0]=original;assertEquals(new ModelGeometryCache.Definition(true,1),cache.definition(model));assertEquals(1,cache.retainedQuads());
        cube.polygons[0]=null;assertFalse(cache.definition(model).compatible());cube.polygons[0]=original;
        assertTrue(cache.definition(model).compatible());assertEquals(1,cache.retainedQuads());assertEquals(0,model.animations);
    }
    @Test void detectsRetainedMutableHierarchyCollectionsAndKeepsExactInstanceCountsAndOrder(){
        Fixture fixture=new Fixture();Part root=fixture.part(cube()),child=fixture.part(cube());CountingModel model=new CountingModel(root.part);
        ModelGeometryCache cache=fixture.cache(8);assertEquals(1,cache.definition(model).maximumQuads());
        root.children.put("child",child.part);assertEquals(2,cache.definition(model).maximumQuads());assertEquals(2,cache.retainedQuads());
        root.cubes.add(cube());assertEquals(3,cache.definition(model).maximumQuads());assertEquals(3,cache.retainedQuads());
        root.cubes.removeFirst();assertEquals(2,cache.definition(model).maximumQuads());assertEquals(2,cache.retainedQuads());
        root.children.clear();assertEquals(1,cache.definition(model).maximumQuads());assertEquals(1,cache.retainedQuads());
        root.children.put("cycle",root.part);assertFalse(cache.definition(model).compatible());root.children.clear();
        assertEquals(new ModelGeometryCache.Definition(true,1),cache.definition(model));assertEquals(0,model.animations);
    }
    @Test void exhaustedAdmissionBudgetRecoversWhenChangedGeometryReleasesCapacity(){
        Fixture fixture=new Fixture();Part a=fixture.part(cube()),b=fixture.part(cube());CountingModel first=new CountingModel(a.part),second=new CountingModel(b.part);
        ModelGeometryCache cache=fixture.cache(1);assertTrue(cache.definition(first).compatible());assertFalse(cache.definition(second).compatible());
        ModelPart.Polygon saved=a.cubes.getFirst().polygons[0];a.cubes.getFirst().polygons[0]=null;
        assertFalse(cache.definition(first).compatible());assertEquals(0,cache.retainedQuads());
        assertTrue(cache.definition(second).compatible());assertEquals(1,cache.retainedQuads());
        a.cubes.getFirst().polygons[0]=saved;assertFalse(cache.definition(first).compatible());
        cache.clear();assertEquals(0,cache.models());assertEquals(0,cache.cubes());assertEquals(0,cache.retainedQuads());assertTrue(cache.definition(first).compatible());
    }
    private static ModelPart.Cube cube(){return new ModelPart.Cube(0,0,-8,-8,0,16,16,0,0,0,0,false,32,32,Set.of(Direction.SOUTH));}
    private static final class CountingModel extends Model<Object>{
        int animations;CountingModel(ModelPart root){super(root,id->null);}@Override public void setupAnim(Object state){animations++;}
    }
    private static final class Part {
        final List<ModelPart.Cube> cubes=new ArrayList<>();final Map<String,ModelPart> children=new LinkedHashMap<>();final ModelPart part;
        Part(ModelPart.Cube[] cubes){java.util.Collections.addAll(this.cubes,cubes);part=new ModelPart(this.cubes,children);}
    }
    /** Supplies exactly the caller collections retained by the unmixed game's ModelPart constructor. */
    private static final class Fixture implements ModelGeometryCache.Structure {
        final IdentityHashMap<ModelPart,Part> parts=new IdentityHashMap<>();
        Part part(ModelPart.Cube... cubes){Part result=new Part(cubes);parts.put(result.part,result);return result;}
        ModelGeometryCache cache(int quads){return new ModelGeometryCache(this,8,8,quads,32);}
        @Override public List<ModelPart.Cube> cubes(ModelPart part){return parts.get(part).cubes;}
        @Override public Map<String,ModelPart> children(ModelPart part){return parts.get(part).children;}
    }
}
