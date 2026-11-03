package dev.overhaul.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.*;

/** Device-local mesh buffers and one VkDrawIndexedIndirectCommand. */
public final class GpuMesh implements AutoCloseable {
    final GpuBuffer vertices;
    final GpuBuffer indices;
    final GpuBuffer indirect;
    private final VulkanContext context;
    private final java.util.function.Consumer<GpuMesh> retirement;
    private boolean closed;

    GpuMesh(VulkanContext context, GpuAllocator allocator, ImmediateCommands commands,
            ByteBuffer vertexData, ByteBuffer indexData, int indexCount, java.util.function.Consumer<GpuMesh> retirement) {
        this.context = context;
        this.retirement = retirement;
        if (vertexData.remaining() % 16 != 0 || vertexData.remaining() == 0)
            throw new IllegalArgumentException("vertices must contain xyz/material records");
        if (indexCount < 1 || (long) indexCount * 4 > indexData.remaining())
            throw new IllegalArgumentException("index count exceeds available uint32 indices");
        int vertexCount = vertexData.remaining() / 16;
        ByteBuffer indexView = indexData.duplicate().order(ByteOrder.nativeOrder());
        for (int i = 0; i < indexCount; i++) {
            if (Integer.compareUnsigned(indexView.getInt(indexView.position() + i * 4), vertexCount) >= 0)
                throw new IllegalArgumentException("mesh index exceeds vertex count");
        }
        GpuBuffer vertex = null, index = null, draw = null;
        try {
            vertex = upload(allocator, commands, vertexData, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            ByteBuffer indexSlice = indexData.duplicate();
            indexSlice.limit(indexSlice.position() + indexCount * 4);
            index = upload(allocator, commands, indexSlice, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            ByteBuffer command = ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());
            command.putInt(indexCount).putInt(1).putInt(0).putInt(0).putInt(0).flip();
            draw = upload(allocator, commands, command, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        } catch (RuntimeException | Error failure) {
            if (draw != null) draw.close();
            if (index != null) index.close();
            if (vertex != null) vertex.close();
            throw failure;
        }
        vertices = vertex;
        indices = index;
        indirect = draw;
    }

    private static GpuBuffer upload(GpuAllocator allocator, ImmediateCommands commands, ByteBuffer source, int usage) {
        GpuBuffer destination = allocator.deviceBuffer(source.remaining(), usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try (GpuBuffer staging = allocator.hostBuffer(source.remaining(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            staging.write(source);
            commands.copy(staging, destination, source.remaining());
            return destination;
        } catch (RuntimeException | Error failure) {
            destination.close();
            throw failure;
        }
    }

    @Override public void close() {
        if (closed) return;
        context.requireOwner();
        indirect.close();
        indices.close();
        vertices.close();
        closed = true;
        retirement.accept(this);
    }
}
