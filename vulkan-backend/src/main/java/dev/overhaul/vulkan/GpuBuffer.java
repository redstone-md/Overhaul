package dev.overhaul.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A bounded VMA allocation. A cached mapping stays private and is unmapped before destruction. */
public final class GpuBuffer implements AutoCloseable {
    private final GpuAllocator allocator;
    private final long allocation;
    private final int size;
    private final boolean hostVisible;
    private final int usage;
    private long handle;
    private ByteBuffer mapping;
    private long hostWrittenBytes;
    private final java.util.Set<MappedRange> ranges = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    GpuBuffer(GpuAllocator allocator, long handle, long allocation, int size, boolean hostVisible, int usage) {
        this.allocator = allocator;
        this.handle = handle;
        this.allocation = allocation;
        this.size = size;
        this.hostVisible = hostVisible;
        this.usage = usage;
    }

    public long handle() { requireOpen(); return handle; }
    public int size() { return size; }
    public int usage(){return usage;}
    public VulkanContext context(){requireOpen();return allocator.context();}

    public void write(ByteBuffer source) { write(0, source); }

    /** Writes the source's remaining range, preserving its position/limit and all other destination bytes. */
    public void write(int destinationOffset, ByteBuffer source) {
        requireHost();
        requireUnmapped();
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
        requireUnmapped();
        ByteBuffer copy = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
        ByteBuffer source = mapped();
        allocator.invalidate(allocation, size);
        copy.put(0, source, 0, size);
        return copy;
    }

    /** A direct lease; its borrowed view expires on close. Caller must synchronize GPU accesses first. */
    public MappedRange mapRange(int offset, int length, boolean read, boolean write) {
        requireHost();
        if (!read && !write) throw new IllegalArgumentException("mapping requires read or write access");
        if (offset < 0 || length < 0 || offset > size - length) throw new IllegalArgumentException("map range exceeds allocation");
        for (MappedRange range : ranges)
            if (offset < range.offset + range.length && range.offset < offset + length && (write || range.write))
                throw new IllegalStateException("overlapping mapped write access");
        ByteBuffer data = mapped().slice(offset, length).order(ByteOrder.nativeOrder());
        if (read && length != 0) allocator.invalidate(allocation, offset, length);
        if (!write) data = data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        MappedRange result = new MappedRange(offset, length, write, data);
        ranges.add(result);
        return result;
    }

    private void requireUnmapped() {
        if (!ranges.isEmpty()) throw new IllegalStateException("buffer has live mapped ranges");
    }

    public final class MappedRange implements AutoCloseable {
        private final int offset, length;
        private final boolean write;
        private final ByteBuffer data;
        private boolean closed;
        private MappedRange(int offset, int length, boolean write, ByteBuffer data) {
            this.offset = offset; this.length = length; this.write = write; this.data = data;
        }
        public ByteBuffer buffer() {
            requireOpen();
            if (closed) throw new IllegalStateException("mapped range closed");
            return data;
        }
        @Override public void close() {
            if (closed) return;
            requireOpen();
            if (write && length != 0) { allocator.flush(allocation, offset, length); hostWrittenBytes += length; }
            ranges.remove(this);
            closed = true;
        }
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
        requireUnmapped();
        if (mapping != null) { allocator.unmap(allocation); mapping = null; }
        allocator.destroy(this, handle, allocation);
        handle = 0;
    }
}
