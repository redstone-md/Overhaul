package dev.overhaul.minecraft.model;

import dev.overhaul.minecraft.backendmixin.ModelPartStructureAccess;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/** Renderer-owned metadata validates mutable source geometry without rerunning animation or pose traversal. */
public final class ModelGeometryCache {
    public record Definition(boolean compatible,int maximumQuads){}
    interface Structure {
        List<ModelPart.Cube> cubes(ModelPart part);
        Map<String,ModelPart> children(ModelPart part);
    }
    private static final Definition UNSUPPORTED_MODEL=new Definition(false,0);
    private static final CubeMesh UNSUPPORTED=new CubeMesh(List.of(),new ModelPart.Polygon[0],new ModelPart.Vertex[0][],new int[0]);
    private record CubeMesh(List<ModelQuadGeometry> quads,ModelPart.Polygon[] polygons,ModelPart.Vertex[][] vertices,int[] normals){
        boolean matches(ModelPart.Cube cube){
            if(cube.polygons.length!=polygons.length)return false;
            for(int index=0;index<polygons.length;index++){
                ModelPart.Polygon polygon=cube.polygons[index];if(polygon!=polygons[index])return false;
                ModelPart.Vertex[] current=polygon.vertices();if(current.length!=vertices[index].length)return false;
                for(int vertex=0;vertex<current.length;vertex++)if(current[vertex]!=vertices[index][vertex])return false;
                if(Float.floatToRawIntBits(polygon.normal().x())!=normals[index*3]
                        ||Float.floatToRawIntBits(polygon.normal().y())!=normals[index*3+1]
                        ||Float.floatToRawIntBits(polygon.normal().z())!=normals[index*3+2])return false;
            }
            return true;
        }
    }
    private static final class PartSnapshot {
        final ModelPart part;
        final List<ModelPart.Cube> cubeSource;
        final Map<String,ModelPart> childSource;
        final ModelPart.Cube[] cubes;
        final String[] names;
        final ModelPart[] children;
        PartSnapshot(ModelPart part,List<ModelPart.Cube> cubeSource,Map<String,ModelPart> childSource){
            this.part=part;this.cubeSource=cubeSource;this.childSource=childSource;
            cubes=cubeSource.toArray(new ModelPart.Cube[0]);names=new String[childSource.size()];children=new ModelPart[names.length];int index=0;
            for(Map.Entry<String,ModelPart> child:childSource.entrySet()){names[index]=child.getKey();children[index++]=child.getValue();}
        }
        boolean matches(Structure structure){
            if(structure.cubes(part)!=cubeSource||structure.children(part)!=childSource||cubeSource.size()!=cubes.length||childSource.size()!=children.length)return false;
            for(int index=0;index<cubes.length;index++)if(cubeSource.get(index)!=cubes[index])return false;
            int index=0;for(Map.Entry<String,ModelPart> child:childSource.entrySet()){
                if(!java.util.Objects.equals(child.getKey(),names[index])||child.getValue()!=children[index])return false;index++;
            }
            return true;
        }
    }
    private record ModelSnapshot(List<PartSnapshot> parts,List<ModelPart.Cube> cubes){
        boolean matches(Structure structure){for(PartSnapshot part:parts)if(!part.matches(structure))return false;return true;}
    }
    private final Thread owner=Thread.currentThread();
    private final Structure structure;
    private final int modelLimit,cubeLimit,quadLimit,partLimit;
    private final IdentityHashMap<ModelPart,ModelSnapshot> models=new IdentityHashMap<>();
    private final IdentityHashMap<ModelPart.Cube,CubeMesh> cubes=new IdentityHashMap<>();
    private int retainedQuads,retainedParts,retainedCubeReferences;

    public ModelGeometryCache(){this(new Structure(){
        private ModelPartStructureAccess access(ModelPart part){return(ModelPartStructureAccess)(Object)part;}
        @Override public List<ModelPart.Cube> cubes(ModelPart part){return access(part).overhaul$cubes();}
        @Override public Map<String,ModelPart> children(ModelPart part){return access(part).overhaul$children();}
    },2048,16384,65536,65536);}
    ModelGeometryCache(Structure structure,int modelLimit,int cubeLimit,int quadLimit,int partLimit){
        this.structure=java.util.Objects.requireNonNull(structure);
        if(modelLimit<1||cubeLimit<1||quadLimit<1||partLimit<1)throw new IllegalArgumentException("Positive model cache limits required");
        this.modelLimit=modelLimit;this.cubeLimit=cubeLimit;this.quadLimit=quadLimit;this.partLimit=partLimit;
    }
    public Definition definition(Model<?> model){
        requireOwner();ModelPart root=model.root();ModelSnapshot snapshot=models.get(root);
        if(snapshot!=null&&!snapshot.matches(structure)){clear();snapshot=null;}
        if(snapshot==null){
            if(models.size()>=modelLimit)return UNSUPPORTED_MODEL;
            List<PartSnapshot> parts=new ArrayList<>();List<ModelPart.Cube> ordered=new ArrayList<>();
            Set<ModelPart> path=Collections.newSetFromMap(new IdentityHashMap<>());
            if(!snapshot(root,parts,ordered,path,0))return UNSUPPORTED_MODEL;
            snapshot=new ModelSnapshot(List.copyOf(parts),List.copyOf(ordered));models.put(root,snapshot);retainedParts+=parts.size();retainedCubeReferences+=ordered.size();
        }
        int total=0;boolean compatible=true;
        for(ModelPart.Cube cube:snapshot.cubes()){
            CubeMesh mesh=mesh(cube);compatible&=mesh!=UNSUPPORTED;total=Math.addExact(total,mesh.quads().size());
        }
        return new Definition(compatible,total);
    }
    private boolean snapshot(ModelPart part,List<PartSnapshot> parts,List<ModelPart.Cube> ordered,Set<ModelPart> path,int depth){
        if(part==null||depth>=256||parts.size()>=partLimit-retainedParts||!path.add(part))return false;
        List<ModelPart.Cube> sourceCubes=structure.cubes(part);Map<String,ModelPart> sourceChildren=structure.children(part);
        if(sourceCubes==null||sourceChildren==null||sourceCubes.size()>Math.max(cubeLimit,quadLimit)-retainedCubeReferences-ordered.size()||sourceChildren.size()>partLimit-retainedParts-parts.size()-1)return false;
        for(ModelPart.Cube cube:sourceCubes)if(cube==null)return false;
        PartSnapshot current=new PartSnapshot(part,sourceCubes,sourceChildren);parts.add(current);
        Collections.addAll(ordered,current.cubes);
        for(ModelPart child:current.children)if(!snapshot(child,parts,ordered,path,depth+1))return false;
        path.remove(part);return true;
    }
    public List<ModelQuadGeometry> quads(ModelPart.Cube cube){
        requireOwner();CubeMesh mesh=mesh(cube);
        if(mesh==UNSUPPORTED)throw new IllegalStateException("Model geometry became unsupported after preparation; animation cannot be replayed");
        return mesh.quads();
    }
    private CubeMesh mesh(ModelPart.Cube cube){
        if(cube==null||cube.getClass()!=ModelPart.Cube.class)return UNSUPPORTED;
        CubeMesh existing=cubes.get(cube);if(existing!=null&&existing.matches(cube))return existing;
        if(existing!=null){cubes.remove(cube);retainedQuads-=existing.quads().size();}
        if(cubes.size()>=cubeLimit||cube.polygons.length>quadLimit-retainedQuads)return UNSUPPORTED;
        List<ModelQuadGeometry> quads=new ArrayList<>(cube.polygons.length);
        ModelPart.Polygon[] polygons=cube.polygons.clone();ModelPart.Vertex[][] vertices=new ModelPart.Vertex[polygons.length][];int[] normals=new int[polygons.length*3];
        for(int index=0;index<polygons.length;index++){
            ModelPart.Polygon polygon=polygons[index];if(polygon==null||polygon.vertices()==null||polygon.normal()==null)return UNSUPPORTED;
            for(ModelPart.Vertex vertex:polygon.vertices())if(vertex==null)return UNSUPPORTED;
            ModelQuadGeometry quad=ModelQuadGeometry.create(polygon);if(quad==null)return UNSUPPORTED;
            quads.add(quad);vertices[index]=polygon.vertices().clone();
            normals[index*3]=Float.floatToRawIntBits(polygon.normal().x());normals[index*3+1]=Float.floatToRawIntBits(polygon.normal().y());normals[index*3+2]=Float.floatToRawIntBits(polygon.normal().z());
        }
        CubeMesh result=new CubeMesh(List.copyOf(quads),polygons,vertices,normals);cubes.put(cube,result);retainedQuads+=quads.size();return result;
    }
    public void clear(){requireOwner();models.clear();cubes.clear();retainedQuads=retainedParts=retainedCubeReferences=0;}
    public int models(){requireOwner();return models.size();}
    public int cubes(){requireOwner();return cubes.size();}
    public int retainedQuads(){requireOwner();return retainedQuads;}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Model geometry belongs to its render owner");}
}
