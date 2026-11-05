package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.vulkan.GpuBuffer;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Persistent owned upload, preserving captured tier metadata and later translucent-sort index order. */
public final class BlazeMesh implements AutoCloseable {
    private final BlazeQueue queue;
    private final BlazeTransfers transfers;
    private final BlazeSequenceIndices sequences;
    private final Consumer<BlazeMesh> onClose;
    private final boolean stream;
    private BlazeGeometryRange vertices, indices;
    private BlazeMeshState state;
    private boolean closed;
    /** What a terrain draw reads, held here because a walk through state, layout and ranges misses the cache once per object. Zero count: nothing to draw. */
    int drawIndexCount, drawFirstIndex, drawBaseVertex, drawRemainder, drawIndexBytes, drawTopology;
    GpuBuffer drawVertices, drawIndices;
    BlazeMeshState.Layout drawLayout;

    BlazeMesh(BlazeQueue queue, BlazeTransfers transfers, BlazeSequenceIndices sequences, boolean stream, Consumer<BlazeMesh> onClose) { this.queue=queue;this.transfers=transfers;this.sequences=sequences;this.stream=stream;this.onClose=onClose; }

    public void upload(BlazeMeshState next,ByteBuffer bytes,ByteBuffer originalIndices) {
        requireOpen();
        BlazeGeometryRange vertex = null, index = null;
        try {
            if (bytes.remaining() != Math.multiplyExact(next.vertexCount(), next.layout().stride())) {
                throw new IllegalArgumentException("Mesh vertex bytes differ from the actual draw state: "+bytes.remaining()+" bytes for "+next.vertexCount()+" vertices of "+next.layout().stride());
            }
            if (bytes.hasRemaining()) vertex = geometry(bytes);
            int expected=Math.multiplyExact(next.indexCount(),next.indexBytes());
            if (originalIndices!=null&&originalIndices.remaining()!=expected) {
                throw new IllegalArgumentException("Mesh index bytes differ from the actual draw state");
            }
            if(expected>0)index=originalIndices==null?sequences.range(next):geometry(originalIndices);
            release();
            vertices = vertex; indices = index; state = next;
            flatten();
            vertex = null; index = null;
        } finally {
            if (vertex != null) vertex.retire(queue);
            if (index != null) index.retire(queue);
        }
    }

    public void uploadIndices(ByteBuffer bytes) {
        requireOpen();
        if (state == null) throw new IllegalStateException("Index replacement before vertex upload");
        int expected=Math.multiplyExact(state.indexCount(),state.indexBytes());
        // A resort is queued against the mesh it read; a rebuild may have replaced that mesh first, and the new one came sorted.
        if (bytes!=null&&bytes.remaining()!=expected) return;
        BlazeGeometryRange next=expected==0?null:bytes==null?sequences.range(state):geometry(bytes);
        if (indices != null) indices.retire(queue);
        indices = next;
        flatten();
    }

    private void flatten() {
        BlazeMeshState current = state;
        if (current == null || current.indexCount() == 0 || vertices == null || indices == null) {
            drawIndexCount = 0;
            drawVertices = drawIndices = null;
            drawLayout = null;
            return;
        }
        int stride = current.layout().stride();
        drawRemainder = vertices.offset() % stride;
        drawBaseVertex = (vertices.offset() - drawRemainder) / stride;
        drawFirstIndex = indices.offset() / current.indexBytes();
        drawIndexBytes = current.indexBytes();
        drawTopology = current.topology();
        drawVertices = vertices.buffer();
        drawIndices = indices.buffer();
        drawLayout = current.layout();
        drawIndexCount = current.indexCount();
    }

    BlazeMeshState stateOrNull() { return state; }
    BlazeGeometryRange vertexRange() { return vertices; }
    BlazeGeometryRange indexRange() { return indices; }
    public BlazeMeshState state() { requireOpen(); if (state == null) throw new IllegalStateException("Mesh has no upload"); return state; }

    Snapshot snapshot(){return new Snapshot(state(),vertices,indices);}
    /** Captured before the ordinary game VertexBuffer can replace its next upload. */
    static final class Snapshot implements AutoCloseable {
        private final BlazeMeshState state;
        private final BlazeGeometryRange vertices,indices;
        private BlazeBufferPool.Pin vertexUse,indexUse;
        private Snapshot(BlazeMeshState state,BlazeGeometryRange vertices,BlazeGeometryRange indices){
            this.state=state;this.vertices=vertices;this.indices=indices;
            try{if(vertices!=null)vertexUse=vertices.retain();if(indices!=null)indexUse=indices.retain();}
            catch(RuntimeException|Error failure){close();throw failure;}
        }
        BlazeMeshState state(){return state;}
        void record(VkCommandBuffer command){
            if(state.indexCount()==0)return;
            try(var stack=VulkanStack.push()){
                vkCmdBindVertexBuffers(command,0,stack.longs(vertices.buffer().handle()),stack.longs(vertices.offset()));
                vkCmdBindIndexBuffer(command,indices.buffer().handle(),indices.offset(),state.indexBytes()==2?VK_INDEX_TYPE_UINT16:VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexed(command,state.indexCount(),1,0,0,0);
            }
        }
        @Override public void close(){if(vertexUse!=null){vertexUse.close();vertexUse=null;}if(indexUse!=null){indexUse.close();indexUse=null;}}
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Blaze mesh is closed"); }
    private BlazeGeometryRange geometry(ByteBuffer bytes) { return stream?transfers.stream(bytes):transfers.geometry(bytes); }
    private void release() { if (vertices != null) vertices.retire(queue); if (indices != null) indices.retire(queue); vertices = indices = null; flatten(); }
    @Override public void close() { if (!closed) { state = null; release(); closed = true; onClose.accept(this); } }
}
