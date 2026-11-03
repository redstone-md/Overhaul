package dev.overhaul.core;

import dev.overhaul.core.memory.DirectSlabPool;
import dev.overhaul.core.memory.StreamingVertexWriter;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class StreamingVertexWriterTest {
    @Test void arbitrarilyManyRecordsReuseOnePageAndCopyExactBytes(){
        try(DirectSlabPool pool=new DirectSlabPool(37,1)){
            ByteBuffer destination=ByteBuffer.allocate(1200).order(ByteOrder.nativeOrder());
            try(StreamingVertexWriter writer=new StreamingVertexWriter(pool,12,source->{assertTrue(source.isReadOnly());destination.put(source);})){
                for(int record=0;record<100;record++){
                    writer.appendRecord();writer.putFloat(0,record+.25f);writer.putInt(4,record);writer.putShort(8,record*7);writer.putByte(10,record);writer.putByte(11,255-record);
                    assertEquals(1,pool.activeLeases());
                }
                assertEquals(100,writer.recordCount());assertEquals(1200,writer.byteCount());writer.finish();assertEquals(0,pool.activeLeases());
                assertThrows(IllegalStateException.class,writer::appendRecord);
            }
            assertEquals(1200,destination.position());
            for(int record=0;record<100;record++){
                int offset=record*12;assertEquals(record+.25f,destination.getFloat(offset));assertEquals(record,destination.getInt(offset+4));
                assertEquals(record*7,destination.getShort(offset+8));assertEquals((byte)record,destination.get(offset+10));assertEquals((byte)(255-record),destination.get(offset+11));
            }
        }
    }
    @Test void sinkFailureAndAbandonedTailReleaseLeaseWithoutRetryingPartialTransaction(){
        try(DirectSlabPool pool=new DirectSlabPool(8,1)){
            StreamingVertexWriter writer=new StreamingVertexWriter(pool,4,source->{throw new IllegalStateException("upload rejected");});
            writer.appendRecord();writer.putInt(0,1);writer.appendRecord();writer.putInt(0,2);
            assertThrows(IllegalStateException.class,writer::appendRecord);assertEquals(0,pool.activeLeases());assertThrows(IllegalStateException.class,writer::finish);writer.close();
            int[] copied={0};try(StreamingVertexWriter tail=new StreamingVertexWriter(pool,4,source->copied[0]++)){tail.appendRecord();tail.putInt(0,3);}
            assertEquals(0,copied[0]);assertEquals(0,pool.activeLeases());
        }
    }
    @Test void sharedPoolSupportsConcurrentIndependentWritersAndRejectsCrossThreadMutation()throws Exception{
        try(DirectSlabPool pool=new DirectSlabPool(16,2)){
            CountDownLatch ready=new CountDownLatch(2),release=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            Runnable task=()->{try(StreamingVertexWriter writer=new StreamingVertexWriter(pool,8,source->{})){
                writer.appendRecord();writer.putInt(0,7);ready.countDown();release.await();writer.finish();
            }catch(Throwable error){failure.set(error);ready.countDown();}};
            Thread first=new Thread(task),second=new Thread(task);first.start();second.start();ready.await();
            try{assertEquals(2,pool.activeLeases());assertThrows(IllegalStateException.class,()->pool.acquire(8));}finally{release.countDown();first.join();second.join();}
            assertNull(failure.get());assertEquals(0,pool.activeLeases());
            try(StreamingVertexWriter writer=new StreamingVertexWriter(pool,8,source->{})){
                AtomicReference<Throwable> rejected=new AtomicReference<>();Thread stranger=new Thread(()->{try{writer.appendRecord();}catch(Throwable error){rejected.set(error);}});stranger.start();stranger.join();
                assertInstanceOf(IllegalStateException.class,rejected.get());assertThrows(IndexOutOfBoundsException.class,()->{writer.appendRecord();writer.putInt(6,0);});
            }
        }
    }
}
