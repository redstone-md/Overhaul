package dev.overhaul.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A bounded VMA allocation. A cached mapping stays private and is unmapped before destruction. */
public final class GpuBuffer implements AutoCloseable {
    private final GpuAllocator allocator;
    private final long allocation;
    private final int size;
    private final boolean hostVisible;
    private long handle;
    private ByteBuffer mapping;
    private long hostWrittenBytes;

    GpuBuffer(GpuAllocator allocator, long handle, long allocation, int size, boolean hostVisible) {
        this.allocator = allocator;
        this.handle = handle;
        this.allocation = allocation;
        this.size = size;
        this.hostVisible = hostVisible;
    }

    public long handle() { requireOpen(); return handle; }
    public int size() { return size; }

    public void write(ByteBuffer source) { write(0, source); }

    /** Writes the source's remaining range, preserving its position/limit and all other destination bytes. */
    public void write(int destinationOffset, ByteBuffer source) {
        requireHost();
        int length = java.util.Objects.requireNonNull(source, "source").remaining();
        if (destinationOffset < 0 || destinationOffset > size - length)
            throw new IllegalArgumentException("write range exceeds allocation");
        if (length == 0) return;
        mapped().put(destinationOffset, source, source.position(), length);
        allocator.flush(allocation, destinationOffset, length);
        hostWrittenBytes += length;
    }

    long hostWrittenBytes() { requireOpen(); return hostWrittenBytes; }

    public ByteBuffer read() {
        requireHost();
        ByteBuffer copy = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
        ByteBuffer source = mapped();
        allocator.invalidate(allocation, size);
        copy.put(0, source, 0, size);
        return copy;
    }

    private ByteBuffer mapped() {
        if (mapping == null) mapping = allocator.map(allocation, size);
        return mapping;
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
        requireOpen();
        if (mapping != null) { allocator.unmap(allocation); mapping = null; }
        allocator.destroy(this, handle, allocation);
        handle = 0;
    }
}
