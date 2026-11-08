package dev.overhaul.minecraft.blaze;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Exact render-thread cost of the Vulkan boundary, including native calls a sampling profiler misses. Off unless
 * {@code overhaul.blaze.stats} is set; every counter belongs to the render thread.
 */
final class BlazeStats {
    static final boolean ENABLED=Boolean.getBoolean("overhaul.blaze.stats");
    private static final Logger LOGGER=LogUtils.getLogger();
    private static final int WINDOW=600;
    private static long draws,passes,captureNanos,flushes,flushNanos,presentNanos,views,samplers,sections,runs,hidden,hiddenEntities,hiddenBlockEntityLists;
    private static long compactMeshes,fullMeshes,savedBytes;
    private static int frames;
    private static long windowStart,waitStart;

    private BlazeStats() {}
    static long start(){return ENABLED?System.nanoTime():0;}
    static void capture(long start){if(ENABLED){draws++;captureNanos+=System.nanoTime()-start;}}
    static void pass(){if(ENABLED)passes++;}
    static void sections(int count,int groups){if(ENABLED){sections+=count;runs+=groups;}}
    static void hiddenSection(){if(ENABLED)hidden++;}
    static void hiddenEntity(){if(ENABLED)hiddenEntities++;}
    static void hiddenBlockEntities(){if(ENABLED)hiddenBlockEntityLists++;}
    static void sectionMeshCompacted(long saved){if(ENABLED){compactMeshes++;savedBytes+=saved;}}
    static void sectionMeshLeftFull(){if(ENABLED)fullMeshes++;}
    static void flush(long start){if(ENABLED){flushes++;flushNanos+=System.nanoTime()-start;}}
    static void viewCreated(){if(ENABLED)views++;}
    static void samplerCreated(){if(ENABLED)samplers++;}
    /** @param waitTotal the queue's fence wait so far; the window's share of it is the frame time the CPU sat idle */
    static void present(long start,long waitTotal,dev.overhaul.vulkan.GpuAllocator allocator){
        if(!ENABLED)return;
        if(frames==WINDOW-1)LOGGER.info("Blaze memory by heap, live/held: {}",allocator.usage());
        long now=System.nanoTime();presentNanos+=now-start;
        if(windowStart==0){windowStart=now;waitStart=waitTotal;return;}
        if(++frames<WINDOW)return;
        double perFrame=1e6*frames;
        LOGGER.info("Blaze stats per frame: frame {} ms, fence wait {} ms, capture {} ms, flush {} ms, present {} ms; draws {}, passes {}, flushes {}, new views {}, new samplers {}; batched sections {} in {} indirect draws, {} hidden, {} entities hidden, {} block entity lists hidden; so far {} section meshes compact, {} left full, {} MiB saved",
                round((now-windowStart)/perFrame),round((waitTotal-waitStart)/perFrame),round(captureNanos/perFrame),round(flushNanos/perFrame),round(presentNanos/perFrame),
                round((double)draws/frames),round((double)passes/frames),round((double)flushes/frames),round((double)views/frames),round((double)samplers/frames),round((double)sections/frames),round((double)runs/frames),round((double)hidden/frames),round((double)hiddenEntities/frames),round((double)hiddenBlockEntityLists/frames),compactMeshes,fullMeshes,savedBytes>>20);
        draws=passes=captureNanos=flushes=flushNanos=presentNanos=views=samplers=sections=runs=hidden=hiddenEntities=hiddenBlockEntityLists=0;frames=0;windowStart=now;waitStart=waitTotal;
    }
    private static double round(double value){return Math.round(value*100)/100d;}
}
