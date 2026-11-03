package dev.overhaul.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A bounded VMA allocation. Mapping is lexical and cannot outlive the operation. */
public final class GpuBuffer implements AutoCloseable {
    private final GpuAllocator allocator;
    private final long allocation;
    private final int size;
    private final boolean hostVisible;
    private long handle;

    GpuBuffer(GpuAllocator allocator, long handle, long allocation, int size, boolean hostVisible) {
        this.allocator = allocator;
        this.handle = handle;
        this.allocation = allocation;
        this.size = size;
        this.hostVisible = hostVisible;
    }

    public long handle() { requireOpen(); return handle; }
    public int size() { return size; }

    public void write(ByteBuffer source) {
        requireHost();
        if (source.remaining() > size) throw new IllegalArgumentException("source exceeds allocation");
        ByteBuffer mapped = allocator.map(allocation, size);
        try {
            mapped.put(source.duplicate());
            allocator.flush(allocation, size);
        } finally { allocator.unmap(allocation); }
    }

    public ByteBuffer read() {
        requireHost();
        ByteBuffer copy = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
        ByteBuffer mapped = allocator.map(allocation, size);
        try {
            allocator.invalidate(allocation, size);
            copy.put(mapped).flip();
            return copy;
        } finally { allocator.unmap(allocation); }
    }

    private void requireHost() {
        requireOpen();
        if (!hostVisible) throw new IllegalStateException("device-local buffer requires a staging copy");
    }

    private void requireOpen() {
        allocator.requireOpen();
        if (handle == 0) throw new IllegalStateException("GPU buffer is closed");
    }

    @Override public void close() {
        if (handle == 0) return;
        allocator.destroy(this, handle, allocation);
        handle = 0;
    }
}
