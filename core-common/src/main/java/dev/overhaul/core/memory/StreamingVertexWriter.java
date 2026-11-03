package dev.overhaul.core.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Consumer;

/** Thread-confined fixed-record writer. A synchronous sink copies borrowed pages before reuse. */
public final class StreamingVertexWriter implements AutoCloseable {
    private final DirectSlabPool pool;
    private final int stride,pageBytes;
    private final Consumer<ByteBuffer> sink;
    private final Thread thread=Thread.currentThread();
    private DirectSlabPool.Lease lease;
    private ByteBuffer page;
    private int used,current=-1,records;
    private boolean finished,closed;

    public StreamingVertexWriter(DirectSlabPool pool,int stride,Consumer<ByteBuffer> sink){
        this.pool=Objects.requireNonNull(pool);this.sink=Objects.requireNonNull(sink);
        if(stride<1||stride>pool.slabBytes())throw new IllegalArgumentException("Record stride exceeds slab");
        this.stride=stride;pageBytes=pool.slabBytes()/stride*stride;
    }
    public void appendRecord(){
        requireWritable();if(records==Integer.MAX_VALUE)throw new IllegalStateException("Record count exceeds integer range");
        if(page==null){lease=pool.acquire(pageBytes);page=lease.buffer();}
        if(used==pageBytes)flush();current=used;used+=stride;records++;
    }
    public void putFloat(int offset,float value){checkField(offset,Float.BYTES);page.putFloat(current+offset,value);}
    public void putInt(int offset,int value){checkField(offset,Integer.BYTES);page.putInt(current+offset,value);}
    public void putShort(int offset,int value){checkField(offset,Short.BYTES);page.putShort(current+offset,(short)value);}
    public void putByte(int offset,int value){checkField(offset,Byte.BYTES);page.put(current+offset,(byte)value);}
    public int recordCount(){requireThread();return records;}
    public int stride(){return stride;}
    public long byteCount(){return(long)recordCount()*stride;}
    public void finish(){
        requireWritable();try{flush();finished=true;}finally{release();}
    }
    private void flush(){
        if(used==0)return;
        ByteBuffer borrowed=page.asReadOnlyBuffer().order(ByteOrder.nativeOrder());borrowed.limit(used);
        try{sink.accept(borrowed);used=0;current=-1;}
        catch(RuntimeException|Error failure){closed=true;release();throw failure;}
    }
    private void checkField(int offset,int length){
        requireWritable();if(current<0)throw new IllegalStateException("No current record");
        if(offset<0||offset>stride-length)throw new IndexOutOfBoundsException("Field exceeds record");
    }
    private void requireWritable(){requireThread();if(closed||finished)throw new IllegalStateException("Vertex writer is closed or finished");}
    private void requireThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Vertex writer belongs to a different thread");}
    private void release(){if(lease!=null){lease.close();lease=null;page=null;}current=-1;}
    /** Unfinished data is discarded. Already copied pages belong to the sink's transaction. */
    @Override public void close(){requireThread();if(!closed){closed=true;release();}}
}
