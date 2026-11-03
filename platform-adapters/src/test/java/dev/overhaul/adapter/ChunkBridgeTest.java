package dev.overhaul.adapter;

import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ChunkBridgeTest {
    @Test void capturesOnlyOnOwnerAndRevisionsDiscardObsoleteGeometry() throws Exception {
        Thread caller=Thread.currentThread();
        SectionSource source=new SectionSource() {
            @Override public int material(int x,int y,int z) {
                assertSame(caller,Thread.currentThread()); return x==0 && y==0 && z==0 ? 1 : 0;
            }
            @Override public int emission(int x,int y,int z) { return 0; }
        };
        try (ChunkBridge bridge=new ChunkBridge(2,8,64*1024,4,2)) {
            SectionKey key=new SectionKey(0,0,0);
            bridge.capture(key,source);
            long latest=bridge.capture(key,source);
            AtomicReference<Long> delivered=new AtomicReference<Long>();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (delivered.get()==null && System.nanoTime()<end) {
                bridge.drain((position,mesh)-> { assertEquals(36,mesh.indexCount()); delivered.set(mesh.revision()); });
                Thread.yield();
            }
            assertEquals(Long.valueOf(latest),delivered.get());
            while (bridge.pendingTasks()!=0 && System.nanoTime()<end) Thread.yield();
            assertEquals(0,bridge.activeLeases());
            bridge.unload(key);
            assertEquals(0,bridge.trackedSections());
            AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
            Thread foreign=new Thread(()-> { try {bridge.drain((k,m)->{});} catch(Throwable error){failure.set(error);} });
            foreign.start(); foreign.join();
            assertTrue(failure.get() instanceof IllegalStateException);
        }
    }

    @Test void throwingUploadStillReleasesMeshAndUnloadReleasesQueuedWork() throws Exception {
        SectionSource empty=new SectionSource() {
            public int material(int x,int y,int z){return 0;}
            public int emission(int x,int y,int z){return 0;}
        };
        try (ChunkBridge bridge=new ChunkBridge(1,8,1024,2,1)) {
            SectionKey key=new SectionKey(0,0,0);
            bridge.capture(key,empty);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (bridge.pendingTasks()!=0 && System.nanoTime()<end) Thread.yield();
            assertThrows(IllegalArgumentException.class,()->bridge.drain((k,m)->{throw new IllegalArgumentException("upload");}));
            assertEquals(0,bridge.activeLeases());
            bridge.capture(key,empty);
            bridge.unload(key);
            while (bridge.pendingTasks()!=0 && System.nanoTime()<end) Thread.yield();
            assertEquals(0,bridge.activeLeases());
        }
    }
}
