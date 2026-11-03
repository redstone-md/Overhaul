package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.texture.UvMapping;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

/** Tests collection against the game's compiler, UV wrapper, decal generator and centroid decoder. */
@Tag("backend-native")
class ModelFrameCollectorTest {
    @Test void collectorRecordsMatchActualCompilerMappingDecalWhiteColorAndSortingCentroids() throws Exception {
        UvMapping mapping=new UvMapping(){
            @Override public float getU(float value){return .173f+value*.619f;}
            @Override public float getV(float value){return .937f-value*.711f;}
        };
        var sheeted=new PoseStack.Pose();sheeted.translate(.17f,-.11f,.04f);
        sheeted.rotate(new Quaternionf().rotationXYZ(.31f,.43f,-.27f));sheeted.scale(.73f,1.31f,-.91f);
        for(boolean sorted:new boolean[]{false,true}){
            compare(null,null,sorted);compare(mapping,null,sorted);
            compare(mapping,sheeted,sorted); // The actual renderer chooses decal instead of UvMapping when both exist.
        }
    }

    private static void compare(UvMapping mapping,PoseStack.Pose sheeted,boolean sorted) throws Exception {
        var draws=ModelGameOracle.scene();var format=DefaultVertexFormat.ENTITY;
        try(var arena=new ModelInstanceArena();var storage=new ByteBufferBuilder(draws.size()*4*format.getVertexSize())){
            arena.beginGroup();var collector=new ModelFrameCollector(new ModelGeometryCache(),arena);
            var batch=new ModelFrameBatches.Batch(null,sorted?VertexSorting.DISTANCE_TO_ORIGIN:null);
            var builder=new BufferBuilder(storage,PrimitiveTopology.QUADS,format);
            for(var draw:draws){
                var submit=new ModelFeatureRenderer.Submit<Object>(null,draw.pose(),null,null,
                        draw.light(),draw.overlay(),draw.color(),mapping,sheeted);
                collector.configure(submit,batch);
                collector.overhaul$captureCube(draw.cube(),draw.pose(),draw.light(),draw.overlay(),draw.color());
                VertexConsumer reference=sheeted!=null?new SheetedDecalTextureGenerator(builder,sheeted,1f):mapping==null?builder:mapping.wrap(builder);
                draw.cube().compile(draw.pose(),reference,draw.light(),draw.overlay(),draw.color());
            }
            assertEquals(draws.size(),arena.size());assertEquals(draws.size(),batch.records.size());
            ByteBuffer records=copy(arena,"records",draws.size()*InstancedModelPipelines.INSTANCE_STRIDE);
            ByteBuffer centroids=sorted?copy(arena,"centroids",draws.size()*12):null;
            try(var mesh=builder.buildOrThrow()){
                ByteBuffer original=mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
                var centers=new CompactVectorArray(draws.size());
                MeshData.decodeQuadCentroids(original,mesh.drawState().vertexCount(),format,centers,0);
                int vertexStride=format.getVertexSize(),uvOffset=format.getElement("UV0").offset();
                int normalOffset=format.getElement("Normal").offset(),colorOffset=format.getElement("Color").offset();
                int lightOffset=format.getElement("UV2").offset(),overlayOffset=format.getElement("UV1").offset();
                for(int draw=0;draw<draws.size();draw++){
                    assertEquals(draw,batch.records.getInt(draw));int record=draw*InstancedModelPipelines.INSTANCE_STRIDE;
                    int argb=records.getInt(record+108);
                    for(int vertex=0;vertex<4;vertex++){
                        int base=(draw*4+vertex)*vertexStride;
                        raw(original.getFloat(base+uvOffset),records.getFloat(record+72+vertex*8));
                        raw(original.getFloat(base+uvOffset+4),records.getFloat(record+76+vertex*8));
                        int normal=(original.get(base+normalOffset)&255)|((original.get(base+normalOffset+1)&255)<<8)|((original.get(base+normalOffset+2)&255)<<16);
                        assertEquals(normal,records.getInt(record+104));
                        assertEquals((argb>>>16)&255,original.get(base+colorOffset)&255);
                        assertEquals((argb>>>8)&255,original.get(base+colorOffset+1)&255);
                        assertEquals(argb&255,original.get(base+colorOffset+2)&255);
                        assertEquals((argb>>>24)&255,original.get(base+colorOffset+3)&255);
                        assertEquals(original.getInt(base+lightOffset),records.getInt(record+112));
                        assertEquals(original.getInt(base+overlayOffset),records.getInt(record+116));
                    }
                    if(sheeted!=null)assertEquals(0xFFFFFFFF,argb,"Actual decal generator discards tinted vertex color");
                    if(sorted){
                        raw(centers.getX(draw),centroids.getFloat(draw*12));
                        raw(centers.getY(draw),centroids.getFloat(draw*12+4));
                        raw(centers.getZ(draw),centroids.getFloat(draw*12+8));
                    }
                }
            }
        }
    }

    private static ByteBuffer copy(ModelInstanceArena arena,String name,int bytes) throws Exception {
        var field=ModelInstanceArena.class.getDeclaredField(name);field.setAccessible(true);
        ByteBuffer source=((ByteBuffer)field.get(arena)).duplicate();source.position(0).limit(bytes);
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).put(source).flip();
    }
    private static void raw(float expected,float actual){assertEquals(Float.floatToRawIntBits(expected),Float.floatToRawIntBits(actual));}
}
