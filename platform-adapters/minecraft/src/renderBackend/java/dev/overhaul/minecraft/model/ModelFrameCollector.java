package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.texture.UvMapping;
import org.joml.Vector3f;

/** The actual hierarchy calls this sink through its cube hook; animation is never replayed for capture. */
final class ModelFrameCollector implements VertexConsumer,ModelCubeCapture {
    private final ModelGeometryCache geometry;
    private final ModelInstanceArena arena;
    private final float[] uv=new float[8];
    private final Vector3f normal=new Vector3f(),position=new Vector3f(),first=new Vector3f(),opposite=new Vector3f();
    private final UvSink projected=new UvSink();
    private ModelFrameBatches.Batch batch;
    private UvMapping mapping;
    private SheetedDecalTextureGenerator decal;

    ModelFrameCollector(ModelGeometryCache geometry,ModelInstanceArena arena){this.geometry=geometry;this.arena=arena;}
    void configure(ModelFeatureRenderer.Submit<?> submit,ModelFrameBatches.Batch batch){
        this.batch=batch;mapping=submit.uvMapping();
        decal=submit.sheetedDecalPose()==null?null:new SheetedDecalTextureGenerator(projected,submit.sheetedDecalPose(),1f);
    }
    @Override public void overhaul$captureCube(ModelPart.Cube cube,PoseStack.Pose pose,int light,int overlay,int color){
        for(ModelQuadGeometry quad:geometry.quads(cube)){
            pose.transformNormal(quad.normalX(),quad.normalY(),quad.normalZ(),normal);
            for(int vertex=0;vertex<4;vertex++){
                if(decal!=null){
                    transform(pose,quad,vertex,position);projected.vertex=vertex;
                    decal.addVertex(position.x,position.y,position.z);decal.setNormal(normal.x,normal.y,normal.z);
                    if(batch.sorting!=null){if(vertex==0)first.set(position);else if(vertex==2)opposite.set(position);}
                }else{
                    uv[vertex*2]=mapping==null?quad.u(vertex):mapping.getU(quad.u(vertex));
                    uv[vertex*2+1]=mapping==null?quad.v(vertex):mapping.getV(quad.v(vertex));
                }
            }
            int index=arena.write(pose,quad,uv,normal,decal==null?color:-1,light,overlay);batch.records.add(index);
            if(batch.sorting!=null){
                if(decal==null){transform(pose,quad,0,first);transform(pose,quad,2,opposite);}
                arena.centroid(index,(first.x+opposite.x)/2f,(first.y+opposite.y)/2f,(first.z+opposite.z)/2f);
            }
        }
    }
    private static void transform(PoseStack.Pose pose,ModelQuadGeometry quad,int vertex,Vector3f target){
        pose.pose().transformPosition(quad.coordinate(vertex,0),quad.coordinate(vertex,1),quad.coordinate(vertex,2),target);
    }
    private final class UvSink implements VertexConsumer {
        int vertex;
        @Override public VertexConsumer setUv(float u,float v){uv[vertex*2]=u;uv[vertex*2+1]=v;return this;}
        @Override public VertexConsumer addVertex(float x,float y,float z){return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        @Override public VertexConsumer setColor(int color){return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setUv3(float u,float v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
        @Override public VertexConsumer setLineWidth(float width){return this;}
    }
    private static UnsupportedOperationException unsupported(){return new UnsupportedOperationException("Model capture requires the owned cube entry point");}
    @Override public VertexConsumer addVertex(float x,float y,float z){throw unsupported();}
    @Override public VertexConsumer setColor(int r,int g,int b,int a){throw unsupported();}
    @Override public VertexConsumer setColor(int color){throw unsupported();}
    @Override public VertexConsumer setUv(float u,float v){throw unsupported();}
    @Override public VertexConsumer setUv1(int u,int v){throw unsupported();}
    @Override public VertexConsumer setUv2(int u,int v){throw unsupported();}
    @Override public VertexConsumer setUv3(float u,float v){throw unsupported();}
    @Override public VertexConsumer setNormal(float x,float y,float z){throw unsupported();}
    @Override public VertexConsumer setLineWidth(float width){throw unsupported();}
}
