package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuBuffer;

/** Part of a slice shared with other meshes: storage nothing writes after this range was handed out. */
final class BlazePinnedRange implements BlazeGeometryRange {
    private final BlazeBufferPool.Pin pin;
    private final GpuBuffer buffer;
    private final int offset;

    BlazePinnedRange(BlazeBufferPool.Slice slice,int offset){buffer=slice.buffer();this.offset=slice.offset()+offset;pin=slice.retain();}
    @Override public GpuBuffer buffer(){return buffer;}
    @Override public int offset(){return offset;}
    @Override public BlazeBufferPool.Pin retain(){return pin.share();}
    @Override public void retire(BlazeQueue queue){close();}
    @Override public void close(){pin.close();}
}
