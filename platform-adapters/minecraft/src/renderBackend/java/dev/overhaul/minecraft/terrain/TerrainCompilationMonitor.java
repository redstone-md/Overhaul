package dev.overhaul.minecraft.terrain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Compiler-owned counters; explicit bounded watches support live rebuild verification without a world registry. */
public final class TerrainCompilationMonitor {
    private static final int WATCH_LIMIT=8;
    private record Watch(long section,AtomicLong completions) {}
    private final Thread constructorThread=Thread.currentThread();
    private final AtomicLong started=new AtomicLong(),completed=new AtomicLong(),failed=new AtomicLong();
    private final AtomicLong modelBlocks=new AtomicLong(),fluidBlocks=new AtomicLong(),vertexBytes=new AtomicLong(),quads=new AtomicLong();
    private final AtomicLong ownerCompiles=new AtomicLong(),offOwnerCompiles=new AtomicLong(),overhaulWorkerCompiles=new AtomicLong();
    private final AtomicReferenceArray<Watch> watched=new AtomicReferenceArray<>(WATCH_LIMIT);
    private volatile String lastThread="none",lastFailure="";

    public void begin(long section) {
        Thread current=Thread.currentThread();
        started.incrementAndGet();lastThread=current.getName();
        if(current==constructorThread)ownerCompiles.incrementAndGet();else offOwnerCompiles.incrementAndGet();
        if(current.getName().startsWith("overhaul-terrain-"))overhaulWorkerCompiles.incrementAndGet();
    }
    public void completed(long section,int models,int fluids,long bytes,long emittedQuads) {
        modelBlocks.addAndGet(models);fluidBlocks.addAndGet(fluids);vertexBytes.addAndGet(bytes);quads.addAndGet(emittedQuads);
        for(int i=0;i<WATCH_LIMIT;i++){Watch watch=watched.get(i);if(watch!=null&&watch.section()==section)watch.completions().incrementAndGet();}
        completed.incrementAndGet();
    }
    public void failed(long section,Throwable failure) { lastFailure=failure.toString();failed.incrementAndGet(); }
    public void watch(long section) {
        for(;;){
            int empty=-1;
            for(int i=0;i<WATCH_LIMIT;i++){Watch watch=watched.get(i);if(watch!=null&&watch.section()==section)return;if(watch==null&&empty<0)empty=i;}
            if(empty<0)throw new IllegalStateException("Terrain observation limit reached");
            if(watched.compareAndSet(empty,null,new Watch(section,new AtomicLong())))return;
        }
    }
    public long completions(long section) {
        for(int i=0;i<WATCH_LIMIT;i++){Watch watch=watched.get(i);if(watch!=null&&watch.section()==section)return watch.completions().get();}
        throw new IllegalStateException("Register a section watch before observing rebuilds");
    }
    public Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();
        result.put("started",started.get());result.put("completed",completed.get());result.put("failed",failed.get());
        result.put("modelBlocks",modelBlocks.get());result.put("fluidBlocks",fluidBlocks.get());result.put("vertexBytes",vertexBytes.get());result.put("quads",quads.get());
        result.put("constructorThreadCompiles",ownerCompiles.get());result.put("offConstructorThreadCompiles",offOwnerCompiles.get());
        result.put("overhaulWorkerCompiles",overhaulWorkerCompiles.get());result.put("lastThread",lastThread);result.put("lastFailure",lastFailure);
        return Map.copyOf(result);
    }
}
