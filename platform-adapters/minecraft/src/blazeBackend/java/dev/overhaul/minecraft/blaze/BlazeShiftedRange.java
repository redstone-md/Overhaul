package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuBuffer;

/** A slice of its own that starts a few bytes in, so what it holds begins on a multiple of the vertex stride. */
final class BlazeShiftedRange implements BlazeGeometryRange {
    private final BlazeBufferPool.Slice slice;
    private final int shift;

    BlazeShiftedRange(BlazeBufferPool.Slice slice, int shift) {
        this.slice = slice;
        this.shift = shift;
    }

    @Override public GpuBuffer buffer() { return slice.buffer(); }
    @Override public int offset() { return slice.offset() + shift; }
    @Override public BlazeBufferPool.Pin retain() { return slice.retain(); }
    @Override public void retire(BlazeQueue queue) { slice.retire(queue); }
    @Override public void close() { slice.close(); }
}
