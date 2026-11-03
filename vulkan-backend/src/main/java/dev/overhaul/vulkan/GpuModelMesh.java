package dev.overhaul.vulkan;

import dev.overhaul.core.model.ModelMesh;
import dev.overhaul.core.model.ModelTranslucentSorter;
import dev.overhaul.core.model.RenderLayer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import static org.lwjgl.vulkan.VK10.*;

/** Device-local model vertices; only the owned index lane changes for transparent sorting. */
public final class GpuModelMesh implements AutoCloseable {
    private final VulkanContext context;
    private final Consumer<GpuModelMesh> onClose;
    private final double originX,originY,originZ;
    private final ModelTranslucentSorter sorter;
    private final ByteBuffer indexScratch,translucentScratch;
    private final int[] counts=new int[RenderLayer.values().length];
    private double lastX=Double.NaN,lastY=Double.NaN,lastZ=Double.NaN;
    GpuBuffer vertices,indices,indirect;
    private boolean closed;

    GpuModelMesh(VulkanContext context,GpuAllocator allocator,ImmediateCommands commands,ModelMesh mesh,Consumer<GpuModelMesh> onClose) {
        this.context=context;this.onClose=onClose;
        originX=mesh.originX();originY=mesh.originY();originZ=mesh.originZ();sorter=mesh.newTranslucentSorter();
        ByteBuffer vertexData=mesh.vertexBuffer(),indexData=mesh.indexBuffer();
        indexScratch=ByteBuffer.allocateDirect(indexData.remaining()).order(ByteOrder.nativeOrder());indexScratch.put(indexData).flip();
        ByteBuffer translucent=indexScratch.duplicate().order(ByteOrder.nativeOrder());
        translucent.position(mesh.firstIndex(RenderLayer.TRANSLUCENT)*4);
        translucentScratch=translucent.slice().order(ByteOrder.nativeOrder());
        try {
            vertices=allocator.deviceBuffer(Math.max(1,vertexData.remaining()),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            if (vertexData.hasRemaining()) try(GpuBuffer staging=allocator.hostBuffer(vertexData.remaining(),VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
                staging.write(vertexData);commands.copy(staging,vertices,vertexData.remaining());
            }
            indices=allocator.hostBuffer(Math.max(1,indexScratch.remaining()),VK_BUFFER_USAGE_INDEX_BUFFER_BIT);indices.write(indexScratch);
            indirect=allocator.hostBuffer(RenderLayer.values().length*20,VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
            ByteBuffer draws=ByteBuffer.allocate(RenderLayer.values().length*20).order(ByteOrder.nativeOrder());
            for(RenderLayer layer:RenderLayer.values()){
                counts[layer.ordinal()]=mesh.indexCount(layer);
                draws.putInt(mesh.indexCount(layer)).putInt(1).putInt(mesh.firstIndex(layer)).putInt(0).putInt(0);
            }
            draws.flip();indirect.write(draws);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    void sort(ModelCamera camera){
        context.requireOwner();if(closed)throw new IllegalStateException("Model mesh closed");
        if(sorter.indexCount()==0 || (lastX==camera.x()&&lastY==camera.y()&&lastZ==camera.z()))return;
        translucentScratch.clear();sorter.writeSortedIndices(translucentScratch,camera.x(),camera.y(),camera.z());
        indices.write(indexScratch);lastX=camera.x();lastY=camera.y();lastZ=camera.z();
    }
    int indexCount(RenderLayer layer){return counts[layer.ordinal()];}
    public double originX(){return originX;}public double originY(){return originY;}public double originZ(){return originZ;}
    @Override public void close(){
        if(closed)return;context.requireOwner();
        if(indirect!=null)indirect.close();if(indices!=null)indices.close();if(vertices!=null)vertices.close();
        closed=true;onClose.accept(this);
    }
}
