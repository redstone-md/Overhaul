package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.ImageCommand;
import dev.overhaul.vulkan.ImageCopies;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.VulkanContext;
import java.util.BitSet;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Consecutive small uploads into one image, recorded as one copy command with one barrier. Vulkan forbids overlapping
 * regions in a copy, so a coarse tile grid per mip sends any possible overlap to the next batch and keeps the order.
 */
final class BlazeUploadBatch implements BlazeQueue.Operation {
    static final int MAX_BYTES=256*1024;
    private static final int MAX_REGIONS=4096,TILE=16;
    private final GpuImage target;
    private final BlazeBufferPool.Slice chunk;
    private final BlazeBufferPool.Pin pin;
    private final ImageCopies copies;
    private final ImageCommand command;
    private final BitSet[] occupied;
    private int layer=-1;
    private boolean closed;

    /** Takes over a tile grid per mip from the batch before it, which can no longer accept regions. */
    BlazeUploadBatch(VulkanContext context,GpuImage target,BlazeBufferPool.Slice chunk,BitSet[] tiles){
        this.target=target;this.chunk=chunk;pin=chunk.retain();occupied=tiles;
        for(BitSet grid:tiles)if(grid!=null)grid.clear();
        copies=new ImageCopies(context,chunk.buffer(),target);
        command=ImageCommand.copyBufferToImage(context,copies);
    }
    /** Claims the region's tiles when it can join; false leaves the batch untouched. */
    boolean accepts(GpuImage image,BlazeBufferPool.Slice next,ImageRegion region){
        if(image!=target||next!=chunk||copies.size()==MAX_REGIONS||layer!=-1&&region.arrayLayer()!=layer)return false;
        int mip=region.mipLevel(),tile=Math.max(1,TILE>>mip),columns=target.width(mip)/tile+1;
        int left=region.x()/tile,right=(region.x()+region.width()-1)/tile,bottom=region.y()/tile,top=(region.y()+region.height()-1)/tile;
        BitSet tiles=occupied[mip];
        if(tiles==null)tiles=occupied[mip]=new BitSet();
        for(int y=bottom;y<=top;y++){
            int row=y*columns,taken=tiles.nextSetBit(row+left);
            if(taken>=0&&taken<=row+right)return false;
        }
        for(int y=bottom;y<=top;y++)tiles.set(y*columns+left,y*columns+right+1);
        layer=region.arrayLayer();
        return true;
    }
    void add(int chunkOffset,int pitch,ImageRegion region){copies.add((long)chunk.offset()+chunkOffset,pitch,region);}
    @Override public void record(VkCommandBuffer nativeCommand){command.record(nativeCommand);}
    @Override public void submitted(FrameRing.Submission submission){command.complete();target.retainUntil(submission);}
    @Override public void close(){
        if(closed)return;closed=true;
        try{command.close();}finally{pin.close();}
    }
}
