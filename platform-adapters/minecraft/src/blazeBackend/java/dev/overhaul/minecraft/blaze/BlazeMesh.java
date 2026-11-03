package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuBuffer;
import dev.overhaul.vulkan.ImmediateCommands;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Persistent owned upload, preserving the exact MeshData and later translucent-sort index order. */
public final class BlazeMesh implements AutoCloseable {
    private final GpuAllocator allocator;
    private final ImmediateCommands commands;
    private final Consumer<BlazeMesh> onClose;
    private GpuBuffer vertices, indices;
    private MeshData.DrawState state;
    private boolean closed;

    BlazeMesh(GpuAllocator allocator, ImmediateCommands commands, Consumer<BlazeMesh> onClose) { this.allocator = allocator; this.commands = commands; this.onClose=onClose; }

    public void upload(MeshData source) {
        requireOpen();
        GpuBuffer vertex = null, index = null;
        try (source) {
            MeshData.DrawState next = source.drawState();
            if (next.vertexCount() < 0 || next.indexCount() < 0) throw new IllegalArgumentException("Negative mesh count");
            ByteBuffer bytes = source.vertexBuffer();
            if (bytes.remaining() != Math.multiplyExact(next.vertexCount(), next.format().getVertexSize())) {
                throw new IllegalArgumentException("Mesh vertex bytes differ from the actual draw state");
            }
            if (bytes.hasRemaining()) vertex = upload(bytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            ByteBuffer originalIndices = source.indexBuffer();
            ByteBuffer data = originalIndices == null ? sequential(next) : originalIndices;
            if (data.remaining() != Math.multiplyExact(next.indexCount(), indexBytes(next.indexType()))) {
                throw new IllegalArgumentException("Mesh index bytes differ from the actual draw state");
            }
            if (data.hasRemaining()) index = upload(data, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            release();
            vertices = vertex; indices = index; state = next;
            vertex = null; index = null;
        } finally {
            if (vertex != null) vertex.close();
            if (index != null) index.close();
        }
    }

    public void uploadIndices(ByteBufferBuilder.Result source) {
        requireOpen();
        try (source) {
            if (state == null) throw new IllegalStateException("Index replacement before vertex upload");
            ByteBuffer bytes = source.byteBuffer();
            if (bytes.remaining() != Math.multiplyExact(state.indexCount(), indexBytes(state.indexType()))) {
                throw new IllegalArgumentException("Sorted index byte count changed");
            }
            GpuBuffer next = bytes.hasRemaining() ? upload(bytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT) : null;
            if (indices != null) indices.close();
            indices = next;
        }
    }

    MeshData.DrawState state() { requireOpen(); if (state == null) throw new IllegalStateException("Mesh has no upload"); return state; }

    void record(VkCommandBuffer command) {
        MeshData.DrawState draw = state();
        if (draw.indexCount() == 0) return;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            vkCmdBindVertexBuffers(command, 0, stack.longs(vertices.handle()), stack.longs(0));
            vkCmdBindIndexBuffer(command, indices.handle(), 0, draw.indexType() == VertexFormat.IndexType.SHORT ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexed(command, draw.indexCount(), 1, 0, 0, 0);
        }
    }

    private GpuBuffer upload(ByteBuffer bytes, int usage) {
        GpuBuffer result = allocator.deviceBuffer(bytes.remaining(), usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try (GpuBuffer staging = allocator.hostBuffer(bytes.remaining(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            staging.write(bytes.duplicate()); commands.copy(staging, result, bytes.remaining()); return result;
        } catch (RuntimeException | Error failure) { result.close(); throw failure; }
    }

    private static ByteBuffer sequential(MeshData.DrawState state) {
        ByteBuffer result = ByteBuffer.allocateDirect(Math.multiplyExact(state.indexCount(), indexBytes(state.indexType()))).order(ByteOrder.nativeOrder());
        int[] corners = state.mode() == VertexFormat.Mode.LINES ? new int[]{0, 1, 2, 3, 2, 1} : new int[]{0, 1, 2, 2, 3, 0};
        boolean quads = state.mode() == VertexFormat.Mode.QUADS || state.mode() == VertexFormat.Mode.LINES;
        for (int index = 0; index < state.indexCount(); index++) {
            int vertex = quads ? (index / 6) * 4 + corners[index % 6] : index;
            if (vertex >= state.vertexCount()) throw new IllegalArgumentException("Sequential index exceeds vertex count");
            if (state.indexType() == VertexFormat.IndexType.SHORT) result.putShort((short) vertex);
            else result.putInt(vertex);
        }
        return result.flip();
    }

    private static int indexBytes(VertexFormat.IndexType type) { return type == VertexFormat.IndexType.SHORT ? 2 : 4; }
    private void requireOpen() { if (closed) throw new IllegalStateException("Blaze mesh is closed"); }
    private void release() { if (vertices != null) vertices.close(); if (indices != null) indices.close(); vertices = indices = null; }
    @Override public void close() { if (!closed) { release(); state = null; closed = true; onClose.accept(this); } }
}
