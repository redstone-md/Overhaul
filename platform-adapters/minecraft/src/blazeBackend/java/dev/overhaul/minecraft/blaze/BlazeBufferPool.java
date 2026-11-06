package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Bounded buffer pages with fence-retired, coalescing suballocations. Owner-thread only. */
final class BlazeBufferPool implements AutoCloseable {
    private final GpuAllocator allocator;
    private final BlazeQueue queue;
    private final boolean host;
    private final int usage,pageSize,alignment;
    private final long budget;
    private final List<Page> pages=new ArrayList<>();
    private long allocated;
    private boolean closed;

    BlazeBufferPool(GpuAllocator allocator,BlazeQueue queue,boolean host,int usage,int pageSize,long budget,int alignment){
        if(pageSize<1||budget<pageSize||alignment<1)throw new IllegalArgumentException("Invalid native buffer pool bounds");
        this.allocator=allocator;this.queue=queue;this.host=host;this.usage=usage;this.pageSize=pageSize;this.budget=budget;this.alignment=alignment;
    }
    Slice acquire(int bytes){
        if(closed)throw new IllegalStateException("Buffer pool is closed");
        if(bytes<1)throw new IllegalArgumentException("Empty native buffer slice");
        int reserved=align(bytes);
        Slice slice=find(bytes,reserved);if(slice!=null)return slice;
        int capacity=Math.max(pageSize,reserved);
        if(allocated+capacity>budget){
            queue.reclaim();slice=find(bytes,reserved);if(slice!=null)return slice;
            // Completely unused pages can be replaced by a larger contiguous allocation.
            var iterator=pages.iterator();while(iterator.hasNext()){
                Page page=iterator.next();if(page.active==0){page.buffer.close();allocated-=page.buffer.size();iterator.remove();}
            }
            if(allocated+capacity>budget)throw new IllegalStateException("Live Blaze buffer storage exceeds bounded "+budget+" byte pool");
        }
        Page page=new Page(host?allocator.hostBuffer(capacity,usage):allocator.deviceBuffer(capacity,usage));
        pages.add(page);allocated+=capacity;return page.acquire(bytes,reserved);
    }
    /** Every slice starts on a multiple of this. */
    int alignment(){return alignment;}
    private Slice find(int bytes,int reserved){for(Page page:pages){Slice slice=page.acquire(bytes,reserved);if(slice!=null)return slice;}return null;}
    private int align(int bytes){return Math.toIntExact(((long)bytes+alignment-1)/alignment*alignment);}
    @Override public void close(){
        if(closed)return;
        for(Page page:pages)if(page.active!=0)throw new IllegalStateException("Closing native buffer pool with live slices");
        for(Page page:pages)page.buffer.close();pages.clear();allocated=0;closed=true;
    }
    final class Slice implements BlazeGeometryRange {
        private final Page page;
        private final int offset,bytes,reserved;
        private int references=1;
        private boolean released;
        private Slice(Page page,int offset,int bytes,int reserved){this.page=page;this.offset=offset;this.bytes=bytes;this.reserved=reserved;}
        @Override public GpuBuffer buffer(){if(references==0)throw new IllegalStateException("Expired native buffer slice");return page.buffer;}
        @Override public int offset(){return offset;}
        int size(){return bytes;}
        void write(ByteBuffer source){if(released)throw new IllegalStateException("Expired native buffer slice owner");if(source.remaining()!=bytes)throw new IllegalArgumentException("Slice upload size differs");buffer().write(offset,source);}
        @Override public Pin retain(){if(released)throw new IllegalStateException("Expired native buffer slice owner");references=Math.addExact(references,1);return new Pin(this);}
        @Override public void retire(BlazeQueue queue){queue.retire(this);}
        private void releaseReference(){if(--references==0)page.release(offset,reserved);}
        @Override public void close(){if(!released){released=true;releaseReference();}}
    }
    final class Pin implements AutoCloseable {
        private Slice slice;
        private Pin(Slice slice){this.slice=slice;}
        /** Another hold on the slice, which stays valid after the slice's owner has let go. */
        Pin share(){slice.references=Math.addExact(slice.references,1);return new Pin(slice);}
        @Override public void close(){if(slice!=null){Slice old=slice;slice=null;old.releaseReference();}}
    }
    /**
     * Free ranges by offset for coalescing, and by size so an acquire takes the smallest fit without walking fragments.
     * Both orders are sorted long arrays when {@link BlazeOptimization#POOL_RANGES} is on: the boxed tree the offsets
     * lived in cost a pointer chase per level on every acquire.
     */
    private static final boolean FLAT=BlazeOptimization.POOL_RANGES.on();
    private final class Page {
        private final GpuBuffer buffer;
        private final TreeMap<Integer,Integer> free=FLAT?null:new TreeMap<>();
        /** offset<<32|size keys. */
        private final BlazeSortedLongs byOffset=FLAT?new BlazeSortedLongs():null;
        /** size<<32|offset keys: a binary search finds the best fit with no allocation. */
        private final BlazeSortedLongs bySize=new BlazeSortedLongs();
        private int active;
        private Page(GpuBuffer buffer){this.buffer=buffer;add(0,buffer.size());}
        private Slice acquire(int bytes,int reserved){
            int fragments=bySize.size();
            if(fragments==0||bySize.get(fragments-1)>>>32<reserved)return null;
            long fit=bySize.get(bySize.slot((long)reserved<<32));
            int offset=(int)fit,size=(int)(fit>>>32);
            remove(offset,size);
            if(size!=reserved)add(offset+reserved,size-reserved);
            active++;return new Slice(this,offset,bytes,reserved);
        }
        private void release(int offset,int size){
            if(FLAT){releaseFlat(offset,size);return;}
            var before=free.lowerEntry(offset);
            if(before!=null&&before.getKey()+before.getValue()==offset){offset=before.getKey();size+=before.getValue();remove(before.getKey(),before.getValue());}
            var after=free.ceilingEntry(offset);
            if(after!=null&&offset+size==after.getKey()){size+=after.getValue();remove(after.getKey(),after.getValue());}
            add(offset,size);active--;
        }
        private void releaseFlat(int offset,int size){
            int at=byOffset.slot((long)offset<<32);
            if(at>0){
                long before=byOffset.get(at-1);int start=(int)(before>>>32),length=(int)before;
                if(start+length==offset){remove(start,length);offset=start;size+=length;at--;}
            }
            if(at<byOffset.size()){
                long after=byOffset.get(at);int start=(int)(after>>>32),length=(int)after;
                if(offset+size==start){remove(start,length);size+=length;}
            }
            add(offset,size);active--;
        }
        private void add(int offset,int size){
            if(FLAT)byOffset.add((long)offset<<32|size);else free.put(offset,size);
            bySize.add((long)size<<32|offset);
        }
        private void remove(int offset,int size){
            if(FLAT)byOffset.remove((long)offset<<32|size);else free.remove(offset);
            bySize.remove((long)size<<32|offset);
        }
    }
}
