package dev.overhaul.minecraft.terrain;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.culling.TerrainGeometryBuilder;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Per-compilation transaction. Pages are copied once into the normal game-owned mesh upload storage. */
public final class LayerMeshBuilder implements AutoCloseable {
    private final SectionBufferBuilderPack pack;
    private final DirectSlabPool pool;
    private final TerrainGeometryBuilder geometry=new TerrainGeometryBuilder();
    private final Map<ChunkSectionLayer,TerrainVertexConsumer> layers=new EnumMap<>(ChunkSectionLayer.class);
    private boolean finished,closed;

    public LayerMeshBuilder(SectionBufferBuilderPack pack,DirectSlabPool pool){this.pack=Objects.requireNonNull(pack);this.pool=Objects.requireNonNull(pool);}
    public VertexConsumer output(ChunkSectionLayer layer){
        requireOpen();return layers.computeIfAbsent(layer,key->new TerrainVertexConsumer(pool,key.vertexFormat(),source->copy(key,source),geometry,key==ChunkSectionLayer.SOLID));
    }
    public BlockQuadOutput blockOutput(boolean forceOpaque){
        requireOpen();return(x,y,z,quad,instance)->output(forceOpaque?ChunkSectionLayer.SOLID:quad.materialInfo().layer()).putBlockBakedQuad(x,y,z,quad,instance);
    }
    public FluidRenderer.Output fluidOutput(){requireOpen();return this::output;}
    private void copy(ChunkSectionLayer layer,ByteBuffer source){
        int bytes=source.remaining();long destination=pack.buffer(layer).reserve(bytes);MemoryUtil.memCopy(MemoryUtil.memAddress(source),destination,bytes);
    }
    public void finish(SectionCompiler.Results results,VertexSorting sorting){
        requireOpen();if(!results.renderedLayers.isEmpty()||results.transparencyState!=null)throw new IllegalArgumentException("Section results already contain geometry");
        Map<ChunkSectionLayer,MeshData> completed=new EnumMap<>(ChunkSectionLayer.class);MeshData.SortState transparency=null;
        try{
            for(var entry:layers.entrySet()){
                ChunkSectionLayer layer=entry.getKey();TerrainVertexConsumer writer=entry.getValue();writer.finish();int vertices=writer.vertexCount();
                if(vertices==0)continue;
                var data=pack.buffer(layer).build();if(data==null)throw new IllegalStateException("Terrain upload buffer is empty");
                MeshData mesh;
                try{mesh=new MeshData(data,new MeshData.DrawState(layer.vertexFormat(),vertices,PrimitiveTopology.QUADS.indexCount(vertices),PrimitiveTopology.QUADS,IndexType.least(vertices)));}
                catch(RuntimeException|Error failure){data.close();throw failure;}
                completed.put(layer,mesh);
                if(layer.translucent())transparency=mesh.sortQuads(pack.buffer(layer),sorting);
            }
            if((Object)results instanceof TerrainGeometryAccess access)access.overhaul$geometry(geometry.build());
            results.renderedLayers.putAll(completed);results.transparencyState=transparency;
            finished=true;
        }catch(RuntimeException|Error failure){closed=true;for(MeshData mesh:completed.values())try{mesh.close();}catch(RuntimeException error){failure.addSuppressed(error);}throw failure;}
        finally{closeWriters();}
    }
    public long vertexCount(){long count=0;for(var layer:layers.values())count+=layer.vertexCount();return count;}
    public long byteCount(){return vertexCount()*TerrainVertexConsumer.STRIDE;}
    private void requireOpen(){if(closed||finished)throw new IllegalStateException("Layer mesh transaction is closed or finished");}
    private void closeWriters(){for(var layer:layers.values())layer.close();}
    /** Pack storage belongs to its existing upload/task owner, including rollback of copied pages. */
    @Override public void close(){if(!closed){closed=true;closeWriters();}}
}
