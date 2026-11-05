package dev.overhaul.minecraft.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One growing index buffer per generated pattern and width, since a shorter draw reads the same prefix. */
final class BlazeSequenceIndices implements AutoCloseable {
    private static final int FIRST_CAPACITY=6144,LARGEST_FOUR_BYTE_GROWTH=1<<24;
    private static final int PATTERNS=BlazeMeshState.IndexPattern.values().length;
    private final BlazeTransfers transfers;
    private final BlazeQueue queue;
    private final BlazeBufferPool.Slice[] slices=new BlazeBufferPool.Slice[PATTERNS*2];
    private final int[] capacities=new int[PATTERNS*2];

    BlazeSequenceIndices(BlazeTransfers transfers,BlazeQueue queue){this.transfers=transfers;this.queue=queue;}

    BlazeGeometryRange range(BlazeMeshState state){
        int count=state.indexCount();
        if(verticesRead(state.pattern(),count)>state.vertexCount())throw new IllegalArgumentException("Sequential index exceeds vertex count");
        int key=state.pattern().ordinal()*2+(state.indexBytes()==4?1:0);
        if(capacities[key]<count)grow(key,state.pattern(),state.indexBytes(),count);
        return new BlazePinnedRange(slices[key],0);
    }
    private void grow(int key,BlazeMeshState.IndexPattern pattern,int indexBytes,int needed){
        int limit=indexBytes==2?indexLimit(pattern):Math.max(needed,LARGEST_FOUR_BYTE_GROWTH);
        if(needed>limit)throw new IllegalArgumentException("Sequential index exceeds its width");
        int capacity=Math.min(limit,Math.max(needed,Math.max(FIRST_CAPACITY,capacities[key]*2)));
        BlazeBufferPool.Slice next=transfers.geometry(Math.multiplyExact(capacity,indexBytes),(target,offset)->fill(pattern,indexBytes,target,offset));
        if(slices[key]!=null)queue.retire(slices[key]);
        slices[key]=next;capacities[key]=capacity;
    }
    private static boolean quads(BlazeMeshState.IndexPattern pattern){return pattern!=BlazeMeshState.IndexPattern.SEQUENTIAL;}
    private static int indexLimit(BlazeMeshState.IndexPattern pattern){return quads(pattern)?98304:65536;}
    private static long verticesRead(BlazeMeshState.IndexPattern pattern,int count){return quads(pattern)?(count/6L+(count%6==0?0:1))*4:count;}
    private static void fill(BlazeMeshState.IndexPattern pattern,int indexBytes,ByteBuffer result,int byteOffset){
        result.order(ByteOrder.nativeOrder());
        int[] corners=pattern==BlazeMeshState.IndexPattern.EXPANDED_LINES?new int[]{0,1,2,3,2,1}:new int[]{0,1,2,2,3,0};
        boolean quads=quads(pattern);
        int first=byteOffset/indexBytes,count=result.remaining()/indexBytes;
        for(int index=first;index<first+count;index++){
            int vertex=quads?index/6*4+corners[index%6]:index;
            if(indexBytes==2)result.putShort((short)vertex);else result.putInt(vertex);
        }
    }
    @Override public void close(){
        for(int key=0;key<slices.length;key++)if(slices[key]!=null){slices[key].close();slices[key]=null;capacities[key]=0;}
    }
}
