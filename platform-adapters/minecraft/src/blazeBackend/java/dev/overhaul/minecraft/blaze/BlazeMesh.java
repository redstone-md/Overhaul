package dev.overhaul.minecraft.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Persistent owned upload, preserving captured tier metadata and later translucent-sort index order. */
public final class BlazeMesh implements AutoCloseable {
    private final BlazeQueue queue;
    private final BlazeTransfers transfers;
    private final Consumer<BlazeMesh> onClose;
    private BlazeBufferPool.Slice vertices, indices;
    private BlazeMeshState state;
    private boolean closed;

    BlazeMesh(BlazeQueue queue, BlazeTransfers transfers, Consumer<BlazeMesh> onClose) { this.queue=queue;this.transfers=transfers;this.onClose=onClose; }

    public void upload(BlazeMeshState next,ByteBuffer bytes,ByteBuffer originalIndices) {
        requireOpen();
        BlazeBufferPool.Slice vertex = null, index = null;
        try {
            if (bytes.remaining() != Math.multiplyExact(next.vertexCount(), next.layout().stride())) {
                throw new IllegalArgumentException("Mesh vertex bytes differ from the actual draw state");
            }
            if (bytes.hasRemaining()) vertex = transfers.geometry(bytes);
            int expected=Math.multiplyExact(next.indexCount(),next.indexBytes());
            if (originalIndices!=null&&originalIndices.remaining()!=expected) {
                throw new IllegalArgumentException("Mesh index bytes differ from the actual draw state");
            }
            if(expected>0)index=originalIndices==null?transfers.geometry(expected,(target,offset)->sequential(next,target,offset)):transfers.geometry(originalIndices);
            release();
            vertices = vertex; indices = index; state = next;
            vertex = null; index = null;
        } finally {
            if (vertex != null) queue.retire(vertex);
            if (index != null) queue.retire(index);
        }
    }

    public void uploadIndices(ByteBuffer bytes) {
        requireOpen();
        if (state == null) throw new IllegalStateException("Index replacement before vertex upload");
        int expected=Math.multiplyExact(state.indexCount(),state.indexBytes());
        if (bytes!=null&&bytes.remaining()!=expected) throw new IllegalArgumentException("Sorted index byte count changed");
        BlazeMeshState captured=state;
        BlazeBufferPool.Slice next=expected==0?null:bytes==null
                ?transfers.geometry(expected,(target,offset)->sequential(captured,target,offset)):transfers.geometry(bytes);
        if (indices != null) queue.retire(indices);
        indices = next;
    }

    public BlazeMeshState state() { requireOpen(); if (state == null) throw new IllegalStateException("Mesh has no upload"); return state; }

    Snapshot snapshot(){return new Snapshot(state(),vertices,indices);}
    /** Captured before the ordinary game VertexBuffer can replace its next upload. */
    static final class Snapshot implements AutoCloseable {
        private final BlazeMeshState state;
        private final BlazeBufferPool.Slice vertices,indices;
        private BlazeBufferPool.Pin vertexUse,indexUse;
        private Snapshot(BlazeMeshState state,BlazeBufferPool.Slice vertices,BlazeBufferPool.Slice indices){
            this.state=state;this.vertices=vertices;this.indices=indices;
            try{if(vertices!=null)vertexUse=vertices.retain();if(indices!=null)indexUse=indices.retain();}
            catch(RuntimeException|Error failure){close();throw failure;}
        }
        BlazeMeshState state(){return state;}
        void record(VkCommandBuffer command){
            if(state.indexCount()==0)return;
            try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
                vkCmdBindVertexBuffers(command,0,stack.longs(vertices.buffer().handle()),stack.longs(vertices.offset()));
                vkCmdBindIndexBuffer(command,indices.buffer().handle(),indices.offset(),state.indexBytes()==2?VK_INDEX_TYPE_UINT16:VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexed(command,state.indexCount(),1,0,0,0);
            }
        }
        @Override public void close(){if(vertexUse!=null){vertexUse.close();vertexUse=null;}if(indexUse!=null){indexUse.close();indexUse=null;}}
    }

    private static void sequential(BlazeMeshState state,ByteBuffer result,int byteOffset) {
        result.order(ByteOrder.nativeOrder());
        int[] corners = state.pattern() == BlazeMeshState.IndexPattern.EXPANDED_LINES ? new int[]{0, 1, 2, 3, 2, 1} : new int[]{0, 1, 2, 2, 3, 0};
        boolean quads = state.pattern() != BlazeMeshState.IndexPattern.SEQUENTIAL;
        int first=byteOffset/state.indexBytes(),count=result.remaining()/state.indexBytes();
        for (int index = first; index < first+count; index++) {
            int vertex = quads ? (index / 6) * 4 + corners[index % 6] : index;
            if (vertex >= state.vertexCount()) throw new IllegalArgumentException("Sequential index exceeds vertex count");
            if (state.indexBytes() == 2) result.putShort((short) vertex);
            else result.putInt(vertex);
        }
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Blaze mesh is closed"); }
    private void release() { if (vertices != null) queue.retire(vertices); if (indices != null) queue.retire(indices); vertices = indices = null; }
    @Override public void close() { if (!closed) { release(); state = null; closed = true; onClose.accept(this); } }
}
