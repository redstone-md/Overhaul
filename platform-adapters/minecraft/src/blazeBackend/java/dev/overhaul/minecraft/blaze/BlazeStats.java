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
    private static int frames;
    private static long windowStart;

    private BlazeStats() {}
    static long start(){return ENABLED?System.nanoTime():0;}
    static void capture(long start){if(ENABLED){draws++;captureNanos+=System.nanoTime()-start;}}
    static void pass(){if(ENABLED)passes++;}
    static void sections(int count,int groups){if(ENABLED){sections+=count;runs+=groups;}}
    static void hiddenSection(){if(ENABLED)hidden++;}
    static void hiddenEntity(){if(ENABLED)hiddenEntities++;}
    static void hiddenBlockEntities(){if(ENABLED)hiddenBlockEntityLists++;}
    static void flush(long start){if(ENABLED){flushes++;flushNanos+=System.nanoTime()-start;}}
    static void viewCreated(){if(ENABLED)views++;}
    static void samplerCreated(){if(ENABLED)samplers++;}
    static void present(long start){
        if(!ENABLED)return;
        long now=System.nanoTime();presentNanos+=now-start;
        if(windowStart==0){windowStart=now;return;}
        if(++frames<WINDOW)return;
        double perFrame=1e6*frames;
        LOGGER.info("Blaze stats per frame: frame {} ms, capture {} ms, flush {} ms, present {} ms; draws {}, passes {}, flushes {}, new views {}, new samplers {}; batched sections {} in {} indirect draws, {} hidden, {} entities hidden, {} block entity lists hidden",
                round((now-windowStart)/perFrame),round(captureNanos/perFrame),round(flushNanos/perFrame),round(presentNanos/perFrame),
                round((double)draws/frames),round((double)passes/frames),round((double)flushes/frames),round((double)views/frames),round((double)samplers/frames),round((double)sections/frames),round((double)runs/frames),round((double)hidden/frames),round((double)hiddenEntities/frames),round((double)hiddenBlockEntityLists/frames));
        draws=passes=captureNanos=flushes=flushNanos=presentNanos=views=samplers=sections=runs=hidden=hiddenEntities=hiddenBlockEntityLists=0;frames=0;windowStart=now;
    }
    private static double round(double value){return Math.round(value*100)/100d;}
}
