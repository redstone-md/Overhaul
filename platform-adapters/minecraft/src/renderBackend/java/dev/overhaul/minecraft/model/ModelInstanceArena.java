package dev.overhaul.minecraft.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded reusable CPU records; uploaded slices use the existing submission-fenced transient arena. */
final class ModelInstanceArena implements AutoCloseable {
    static final int MAX_RECORDS=(8*1024*1024)/InstancedModelPipelines.INSTANCE_STRIDE;
    private final Thread owner=Thread.currentThread();
    private final ByteBuffer records;
    private final ByteBuffer centroids;
    private ModelQuadInstanceWriter writer;
    private boolean closed;

    ModelInstanceArena(){
        records=MemoryUtil.memAlloc(MAX_RECORDS*InstancedModelPipelines.INSTANCE_STRIDE).order(ByteOrder.nativeOrder());
        try{centroids=MemoryUtil.memAlloc(MAX_RECORDS*12).order(ByteOrder.nativeOrder());}
        catch(RuntimeException|Error failure){MemoryUtil.memFree(records);throw failure;}
    }
    void beginGroup(){requireOpen();records.clear();writer=new ModelQuadInstanceWriter(records,MAX_RECORDS);}
    int write(PoseStack.Pose pose,ModelQuadGeometry quad,float[] uv,Vector3fc normal,int color,int light,int overlay){
        requireOpen();int index=writer.written();writer.write(pose,quad,uv,normal,color,light,overlay);return index;
    }
    void centroid(int index,float x,float y,float z){
        requireOpen();int offset=Math.multiplyExact(index,12);
        centroids.putFloat(offset,x).putFloat(offset+4,y).putFloat(offset+8,z);
    }
    int size(){requireOpen();return writer.written();}
    GpuBufferSlice upload(IntArrayList indices,VertexSorting sorting){
        requireOpen();writer.finish(writer.written());int count=indices.size();
        int[] order=null;
        if(sorting!=null){
            var centers=new CompactVectorArray(count);
            for(int index=0;index<count;index++){
                int source=indices.getInt(index)*12;
                centers.set(index,centroids.getFloat(source),centroids.getFloat(source+4),centroids.getFloat(source+8));
            }
            order=sorting.sort(centers);
        }
        int stride=InstancedModelPipelines.INSTANCE_STRIDE;
        var memory=RenderSystem.getDevice().createCommandEncoder().transientMemory();
        try(var mapped=memory.allocateGpuMapped((long)count*stride,4,GpuBuffer.USAGE_VERTEX)){
            ByteBuffer destination=mapped.data();
            for(int index=0;index<count;index++){
                int source=indices.getInt(order==null?index:order[index]);
                destination.put(index*stride,records,source*stride,stride);
            }
            return mapped.slice();
        }
    }
    @Override public void close(){
        requireOwner();if(closed)return;closed=true;writer=null;MemoryUtil.memFree(records);MemoryUtil.memFree(centroids);
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Model records belong to their render owner");}
    private void requireOpen(){requireOwner();if(closed)throw new IllegalStateException("Model record arena is closed");}
}
