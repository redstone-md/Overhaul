package dev.overhaul.minecraft.terrain;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.overhaul.core.memory.DirectSlabPool;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class TerrainVertexParityTest {
    @Test void chainAndFastVertexWritesMatchActualBufferBuilderAcrossHundredsOfPageFlushes(){
        int vertices=4*257;ByteBuffer actual=ByteBuffer.allocate(vertices*TerrainVertexConsumer.STRIDE);
        try(var pool=new DirectSlabPool(85,1);var storage=new ByteBufferBuilder(128);
            var encoded=new TerrainVertexConsumer(pool,DefaultVertexFormat.BLOCK,actual::put)){
            var reference=new BufferBuilder(storage,PrimitiveTopology.QUADS,DefaultVertexFormat.BLOCK);
            emit(vertices,reference);emit(vertices,encoded);encoded.finish();
            try(var expected=reference.buildOrThrow()){
                assertEquals(vertices,encoded.vertexCount());assertEquals(expected.drawState().vertexCount(),encoded.vertexCount());
                assertEquals(0,pool.activeLeases());assertBytes(expected.vertexBuffer(),actual.flip());
            }
        }
    }

    @Test void allLayersForcedOpaqueBakedQuadsAndFluidOutputsMatchNormalMeshUploadAndSorting(){
        var sorting=VertexSorting.byDistance(3,2,1);var expectedResults=new SectionCompiler.Results();var actualResults=new SectionCompiler.Results();
        try(var pool=new DirectSlabPool(113,3);var actualPack=new SectionBufferBuilderPack();var referencePack=new SectionBufferBuilderPack();
            var encoder=new LayerMeshBuilder(actualPack,pool)){
            Map<ChunkSectionLayer,BufferBuilder> reference=new EnumMap<>(ChunkSectionLayer.class);
            java.util.function.Function<ChunkSectionLayer,VertexConsumer> output=layer->reference.computeIfAbsent(layer,key->new BufferBuilder(referencePack.buffer(key),PrimitiveTopology.QUADS,key.vertexFormat()));
            BlockQuadOutput normal=(x,y,z,quad,instance)->output.apply(quad.materialInfo().layer()).putBlockBakedQuad(x,y,z,quad,instance);
            BlockQuadOutput opaque=(x,y,z,quad,instance)->output.apply(ChunkSectionLayer.SOLID).putBlockBakedQuad(x,y,z,quad,instance);
            emitScene(normal,opaque,output::apply);emitScene(encoder.blockOutput(false),encoder.blockOutput(true),encoder.fluidOutput());
            for(var entry:reference.entrySet()){
                MeshData mesh=entry.getValue().buildOrThrow();expectedResults.renderedLayers.put(entry.getKey(),mesh);
                if(entry.getKey().translucent())expectedResults.transparencyState=mesh.sortQuads(referencePack.buffer(entry.getKey()),sorting);
            }
            encoder.finish(actualResults,sorting);assertEquals(0,pool.activeLeases());assertNotNull(actualResults.transparencyState);
            assertEquals(3,actualResults.renderedLayers.size());long count=0;
            for(var layer:ChunkSectionLayer.values()){
                var expected=expectedResults.renderedLayers.get(layer);var actual=actualResults.renderedLayers.get(layer);
                assertNotNull(actual,layer.label());assertEquals(expected.drawState(),actual.drawState());assertBytes(expected.vertexBuffer(),actual.vertexBuffer());
                if(expected.indexBuffer()!=null)assertBytes(expected.indexBuffer(),actual.indexBuffer());count+=actual.drawState().vertexCount();
            }
            assertEquals(count,encoder.vertexCount());assertEquals(count*TerrainVertexConsumer.STRIDE,encoder.byteCount());
            assertThrows(IllegalStateException.class,()->encoder.output(ChunkSectionLayer.SOLID));
        }finally{actualResults.release();expectedResults.release();}
    }

    @Test void incompleteVerticesAndUnsupportedFormatsFailWithoutLeakingPageOwnership(){
        try(var pool=new DirectSlabPool(64,1);var consumer=new TerrainVertexConsumer(pool,DefaultVertexFormat.BLOCK,source->{})){
            consumer.addVertex(1,2,3).setUv(.1f,.2f);assertThrows(IllegalStateException.class,consumer::finish);
            assertThrows(IllegalStateException.class,()->consumer.addVertex(0,0,0));assertEquals(1,pool.activeLeases());
            consumer.close();assertEquals(0,pool.activeLeases());
            assertThrows(UnsupportedOperationException.class,()->new TerrainVertexConsumer(pool,DefaultVertexFormat.ENTITY,source->{}));
        }
        try(var pool=new DirectSlabPool(64,3);var pack=new SectionBufferBuilderPack();var layers=new LayerMeshBuilder(pack,pool)){
            layers.output(ChunkSectionLayer.SOLID).addVertex(1,2,3);var result=new SectionCompiler.Results();
            assertThrows(IllegalStateException.class,()->layers.finish(result,VertexSorting.DISTANCE_TO_ORIGIN));
            assertEquals(0,pool.activeLeases());assertTrue(result.renderedLayers.isEmpty());assertThrows(IllegalStateException.class,()->layers.output(ChunkSectionLayer.CUTOUT));
        }
    }

    private static void emit(int count,VertexConsumer consumer){
        consumer.setColor(1,2,3,4).setUv(9,9).setLight(0); // Setters before the first vertex are ignored by BufferBuilder.
        for(int vertex=0;vertex<count;vertex++){
            float x=vertex*.125f-8,y=(vertex%11)*.1f,z=-(vertex%7)*.25f,u=vertex*.000137f,v=1-vertex*.000173f;int light=((vertex*13&255)<<16)|(vertex*7&255),argb=0x81234567+vertex;
            if((vertex&1)==0)consumer.addVertex(x,y,z,argb,u,v,0xfedcba98,light,-.9f,4,Float.NaN);
            else consumer.addVertex(x,y,z).setUv(u,v).setUv(-7,-8).setColor(argb).setColor(0,0,0,0).setLight(light).setLight(0)
                    .setOverlay(0xffff).setNormal(-.3f,.7f,1.2f).setUv3(.5f,.6f).setLineWidth(9);
        }
    }
    private static void emitScene(BlockQuadOutput normal,BlockQuadOutput opaque,FluidRenderer.Output fluids){
        var instance=new QuadInstance();for(int vertex=0;vertex<4;vertex++){instance.setColor(vertex,0x80132637+vertex*0x172b1d);instance.setLightCoords(vertex,(124+vertex*3)<<16|60+vertex*7);}instance.setOverlayCoords(0xffff1234);
        for(var layer:ChunkSectionLayer.values())normal.put(.125f,2.375f,-.5f,quad(layer),instance);
        normal.put(9.125f,1.25f,7.75f,quad(ChunkSectionLayer.TRANSLUCENT),instance);opaque.put(4,5,6,quad(ChunkSectionLayer.CUTOUT),instance);
        for(var layer:new ChunkSectionLayer[]{ChunkSectionLayer.CUTOUT,ChunkSectionLayer.TRANSLUCENT}){
            VertexConsumer fluid=fluids.getBuilder(layer);for(int vertex=0;vertex<4;vertex++)fluid.addVertex(vertex&1,.8f+(vertex%3)*.01f,vertex>>>1)
                    .setColor(.2f,.35f,.75f,.6f).setUv(vertex*.13f,vertex*.17f).setLight(0x008c0074).setNormal(0,1,0);
        }
    }
    private static BakedQuad quad(ChunkSectionLayer layer){
        return new BakedQuad(new Vector3f(.125f,.2f,-.3f),new Vector3f(.9f,.35f,.1f),new Vector3f(.8f,.95f,.2f),new Vector3f(-.1f,.7f,.05f),
                uv(.125137f,.81437f),uv(.95123f,.81437f),uv(.95123f,.06217f),uv(.125137f,.06217f),Direction.SOUTH,
                new BakedQuad.MaterialInfo(null,layer,null,null,null,2,Direction.SOUTH,5));
    }
    private static long uv(float u,float v){return UVPair.pack(u,v);}
    private static void assertBytes(ByteBuffer expected,ByteBuffer actual){
        assertEquals(expected.remaining(),actual.remaining());for(int index=0;index<expected.remaining();index++)assertEquals(expected.get(expected.position()+index),actual.get(actual.position()+index),"byte "+index);
    }
}
