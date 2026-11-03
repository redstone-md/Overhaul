package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Bounded reusable host-visible arenas. Rewind is legal only after native submission completion. */
final class OverhaulTransientMemory implements TransientMemory,AutoCloseable {
    private static final int BLOCK_BYTES=1024*1024;
    private static final long BUDGET=128L*1024*1024;
    private final OverhaulDeviceBackend owner;
    private final Arena[] arenas={new Arena(),new Arena(),new Arena()};
    private int arenaIndex;
    private List<CpuBlock> cpu=arenas[0].cpu;
    private List<GpuBlock> gpu=arenas[0].gpu;
    private long allocatedBytes;
    private int liveMappings;
    private boolean closed;

    OverhaulTransientMemory(OverhaulDeviceBackend owner) { this.owner=owner; }

    @Override public ByteBuffer allocateCpu(long size,long alignment,long minimum,long elementSize) {
        requireOpen();
        int bytes=allocationSize(size,minimum,elementSize);
        checkAlignment(alignment);
        if (bytes==0) return ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
        for (CpuBlock block:cpu) {
            int offset=aligned(MemoryUtil.memAddress(block.data),block.cursor,alignment);
            if (offset<=block.data.capacity()-bytes) {
                block.cursor=offset+bytes;
                return block.data.slice(offset,bytes).order(ByteOrder.nativeOrder());
            }
        }
        int capacity=aligned(0,Math.max(BLOCK_BYTES,Math.addExact(bytes,Math.toIntExact(alignment))),alignment);
        reserve(capacity);
        ByteBuffer data;
        try { data=MemoryUtil.memAlignedAlloc(Math.max(8,Math.toIntExact(alignment)),capacity).order(ByteOrder.nativeOrder()); }
        catch(RuntimeException|Error failure) { allocatedBytes-=capacity; throw failure; }
        cpu.add(new CpuBlock(data));
        return allocateCpu(size,alignment,minimum,elementSize);
    }

    @Override public GpuBufferSlice.MappedView allocateStaging(long size,long alignment,int usage,long minimum,long elementSize) {
        return mapped(allocateGpu(size,alignment,usage|GpuBuffer.USAGE_COPY_SRC,minimum,elementSize));
    }

    @Override public GpuBufferSlice allocateGpu(long size,long alignment,int usage,long minimum,long elementSize) {
        requireOpen();
        int bytes=allocationSize(size,minimum,elementSize);
        checkAlignment(alignment);
        // Independent host writes must not share a non-coherent atom with earlier GPU reads.
        alignment=Math.max(alignment,owner.queue().mappingAlignment());
        int hostUsage=usage|GpuBuffer.USAGE_MAP_WRITE|GpuBuffer.USAGE_COPY_SRC|GpuBuffer.USAGE_COPY_DST;
        for (GpuBlock block:gpu) {
            if (block.usage!=hostUsage || block.buffer.isClosed()) continue;
            int offset=aligned(0,block.cursor,alignment);
            if (offset<=block.capacity-bytes) {
                block.cursor=offset+bytes;
                return block.buffer.slice(offset,bytes);
            }
        }
        int capacity=Math.max(BLOCK_BYTES,Math.addExact(bytes,Math.toIntExact(alignment)));
        reserve(capacity);
        GpuBuffer buffer;
        try { buffer=owner.createBuffer(()->"overhaul transient arena",hostUsage,capacity); }
        catch(RuntimeException|Error failure) { allocatedBytes-=capacity; throw failure; }
        gpu.add(new GpuBlock(buffer,hostUsage,capacity));
        return allocateGpu(size,alignment,usage,minimum,elementSize);
    }

    @Override public GpuBufferSlice.MappedView allocateGpuMapped(long size,long alignment,int usage,long minimum,long elementSize) {
        return mapped(allocateGpu(size,alignment,usage,minimum,elementSize));
    }

    @Override public GpuBufferSlice uploadStaging(List<ByteBuffer> data,long alignment,int usage,long minimum,long elementSize) {
        return upload(data,alignment,usage|GpuBuffer.USAGE_COPY_SRC,minimum,elementSize);
    }

    @Override public GpuBufferSlice uploadGpu(List<ByteBuffer> data,long alignment,int usage,long minimum,long elementSize) {
        return upload(data,alignment,usage,minimum,elementSize);
    }

    private GpuBufferSlice upload(List<ByteBuffer> data,long alignment,int usage,long minimum,long elementSize) {
        long length=0;
        for (ByteBuffer source:data) length=Math.addExact(length,source.remaining());
        try (GpuBufferSlice.MappedView view=allocateGpuMapped(length,alignment,usage,minimum,elementSize)) {
            for (ByteBuffer source:data) view.data().put(source.duplicate());
            return view.slice();
        }
    }

    @Override public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data,long alignment,int usage) {
        return multiUpload(data,alignment,usage|GpuBuffer.USAGE_COPY_SRC);
    }

    @Override public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data,long alignment,int usage) {
        return multiUpload(data,alignment,usage);
    }

    private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data,long alignment,int usage) {
        List<GpuBufferSlice> slices=new ArrayList<>(data.size());
        for (ByteBuffer source:data) slices.add(upload(List.of(source),alignment,usage,source.remaining(),1));
        return slices;
    }

    private GpuBufferSlice.MappedView mapped(GpuBufferSlice slice) {
        if (slice.length()==0) return new GpuBufferSlice.MappedView(slice,ByteBuffer.allocateDirect(0),()->{});
        GpuBufferSlice.MappedView view=slice.map(false,true);
        liveMappings++;
        return new GpuBufferSlice.MappedView(slice,view.data(),new MappingReturn(view));
    }

    private final class MappingReturn implements Runnable {
        private final GpuBufferSlice.MappedView view;
        private boolean returned;
        private MappingReturn(GpuBufferSlice.MappedView view) { this.view=view; }
        @Override public void run() {
            if (returned) return;
            requireOpen();
            view.close(); liveMappings--; returned=true;
        }
    }

    void finishFrame(dev.overhaul.vulkan.FrameRing.Submission completion) {
        requireOpen();
        if (liveMappings!=0) throw new IllegalStateException("Transient mappings remain open at frame submission");
        arenas[arenaIndex].completion=completion;
        arenaIndex=(arenaIndex+1)%arenas.length;
        Arena next=arenas[arenaIndex];
        owner.queue().await(next.completion,-1L);
        cpu=next.cpu; gpu=next.gpu;
        for (CpuBlock block:cpu) block.cursor=0;
        gpu.removeIf(block-> {
            if (!block.buffer.isClosed()) return false;
            allocatedBytes-=block.capacity;
            return true;
        });
        for (GpuBlock block:gpu) block.cursor=0;
    }

    private void reserve(int bytes) {
        if (allocatedBytes>BUDGET-bytes) throw new IllegalStateException("Transient native memory budget exhausted");
        allocatedBytes+=bytes;
    }

    private static int allocationSize(long size,long minimum,long elementSize) {
        if (size<0 || minimum<0 || elementSize<1) throw new IllegalArgumentException("Invalid allocation dimensions");
        long bytes=Math.max(size,minimum);
        return Math.toIntExact(Math.multiplyExact(Math.floorDiv(Math.addExact(bytes,elementSize-1),elementSize),elementSize));
    }

    private static void checkAlignment(long alignment) {
        if (alignment<1 || alignment>Integer.MAX_VALUE || Long.bitCount(alignment)!=1) throw new IllegalArgumentException("Alignment must be a positive power of two");
    }

    private static int aligned(long base,int cursor,long alignment) {
        return Math.toIntExact(((Math.addExact(base+cursor,alignment-1))&-alignment)-base);
    }

    private void requireOpen() {
        owner.requireOpen();
        if (closed) throw new IllegalStateException("Transient arena is closed");
    }

    @Override public void close() {
        if (closed) return;
        requireOpen(); owner.context().waitIdle();
        if (liveMappings!=0) throw new IllegalStateException("Cannot destroy live transient mappings");
        for (Arena arena:arenas) {
            owner.queue().await(arena.completion,-1L);
            for (GpuBlock block:arena.gpu) block.buffer.close();
            for (CpuBlock block:arena.cpu) MemoryUtil.memAlignedFree(block.data);
            arena.gpu.clear(); arena.cpu.clear();
        }
        allocatedBytes=0; closed=true;
    }

    private static final class Arena {
        private final List<CpuBlock> cpu=new ArrayList<>();
        private final List<GpuBlock> gpu=new ArrayList<>();
        private dev.overhaul.vulkan.FrameRing.Submission completion;
    }

    private static final class CpuBlock {
        private final ByteBuffer data;
        private int cursor;
        private CpuBlock(ByteBuffer data) { this.data=data; }
    }
    private static final class GpuBlock {
        private final GpuBuffer buffer;
        private final int usage,capacity;
        private int cursor;
        private GpuBlock(GpuBuffer buffer,int usage,int capacity) { this.buffer=buffer; this.usage=usage; this.capacity=capacity; }
    }
}
