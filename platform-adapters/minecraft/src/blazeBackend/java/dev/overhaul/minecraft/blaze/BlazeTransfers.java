package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.vulkan.*;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Snapshots CPU sources immediately, records transfers later in the shared graphics lane. */
final class BlazeTransfers {
    private static final int MAX_CHUNK=8*1024*1024,MAX_STREAM=4*1024*1024;
    private final VulkanContext context;
    private final BlazeQueue queue;
    private final BlazeBufferPool host,geometry;
    private final BlazeHostArena uploads,stream;
    /** Mip levels of a 2D image cannot exceed the bits of its size. */
    private final java.util.BitSet[] uploadTiles=new java.util.BitSet[32];
    BlazeTransfers(VulkanContext context,BlazeQueue queue,BlazeBufferPool host,BlazeBufferPool geometry,BlazeHostArena uploads,BlazeHostArena stream){this.context=context;this.queue=queue;this.host=host;this.geometry=geometry;this.uploads=uploads;this.stream=stream;}
    /** Geometry drawn once: written where the GPU reads it, so no copy has to end the render pass. */
    BlazeGeometryRange stream(ByteBuffer source){
        int bytes=source.remaining();
        if(bytes>MAX_STREAM)return geometry(source);
        BlazeBufferPool.Slice chunk=stream.reserve(bytes);
        return new BlazePinnedRange(chunk,stream.write(source,bytes));
    }
    BlazeBufferPool.Slice geometry(ByteBuffer source){
        return geometry(source.remaining(),(target,offset)->target.put(source.slice(source.position()+offset,target.remaining())));
    }
    /**
     * Vertices start on a multiple of their stride, so every section in a page binds the page at offset zero and the
     * sections of one layer share one multi-draw per page instead of one per distinct leftover. The multiple is of a
     * whole quad, so the base vertex a draw then carries keeps {@code gl_VertexID % 4} naming the corner, as packs expect.
     */
    BlazeGeometryRange vertices(ByteBuffer source,int stride){
        int alignment=geometry.alignment(),bytes=source.remaining(),quad=stride*4;
        if(!BlazeOptimization.STRIDE_OFFSETS.on()||alignment%quad==0)return geometry(source);
        int slack=quad-gcd(alignment,quad);
        BlazeBufferPool.Slice destination=geometry.acquire(bytes+slack);
        int shift=(quad-destination.offset()%quad)%quad;
        fill(destination,shift,bytes,(target,offset)->target.put(source.slice(source.position()+offset,target.remaining())));
        return new BlazeShiftedRange(destination,shift);
    }
    private static int gcd(int a,int b){while(b!=0){int t=a%b;a=b;b=t;}return a;}
    BlazeBufferPool.Slice geometry(int bytes,BiConsumer<ByteBuffer,Integer> writer){
        return fill(geometry.acquire(bytes),0,bytes,writer);
    }
    private BlazeBufferPool.Slice fill(BlazeBufferPool.Slice destination,int base,int bytes,BiConsumer<ByteBuffer,Integer> writer){
        try{
            for(int offset=0;offset<bytes;){
                int length=Math.min(MAX_CHUNK,bytes-offset);
                BlazeBufferPool.Slice staging=host.acquire(length);
                try{
                    try(var mapping=staging.buffer().mapRange(staging.offset(),length,false,true)){
                        ByteBuffer target=mapping.buffer();writer.accept(target,offset);
                        if(target.hasRemaining())throw new IllegalStateException("Geometry writer did not fill its bounded upload slice");
                    }
                    BufferCopy operation=new BufferCopy(staging,destination,base+offset);staging=null;
                    queue.add(operation,length);
                }finally{if(staging!=null)staging.close();}
                offset+=length;
            }
            return destination;
        }catch(RuntimeException|Error failure){queue.retire(destination);throw failure;}
    }
    /** Stages host bytes in order with draws, so bytes that earlier queued draws read stay intact until those draws ran. */
    void write(GpuBuffer target,long targetOffset,long source,long bytes){
        for(long done=0;done<bytes;){
            int length=(int)Math.min(MAX_CHUNK,bytes-done);
            BlazeBufferPool.Slice staging=host.acquire(length);
            try{
                staging.write(org.lwjgl.system.MemoryUtil.memByteBuffer(source+done,length));
                BlazeBufferPool.Slice owned=staging;staging=null;
                queue.add(new DeviceCopy(owned.buffer(),owned.offset(),target,targetOffset+done,length,owned),length);
            }finally{if(staging!=null)staging.close();}
            done+=length;
        }
    }
    void copy(GpuBuffer source,long sourceOffset,GpuBuffer target,long targetOffset,long bytes){
        if(bytes>0)queue.add(new DeviceCopy(source,sourceOffset,target,targetOffset,bytes,null),0);
    }
    void upload(GpuImage target,ImageRegion region,ByteBuffer source,int pitch){
        int tight=Math.multiplyExact(region.width(),target.format().bytesPerPixel());
        int stride=pitch==0?tight:pitch;
        if(stride<tight||stride%target.format().bytesPerPixel()!=0)throw new IllegalArgumentException("Invalid image row pitch");
        int bytes=Math.addExact(Math.multiplyExact(region.height()-1,stride),tight);
        if(source.remaining()<bytes)throw new IllegalArgumentException("Truncated image upload");
        if(region.x()<0||region.y()<0||region.width()<1||region.height()<1||region.x()>target.width(region.mipLevel())-region.width()
                ||region.y()>target.height(region.mipLevel())-region.height())throw new IllegalArgumentException("Image upload exceeds mip bounds");
        int rows=Math.max(1,(MAX_CHUNK-tight)/stride+1);
        for(int row=0;row<region.height();){
            int count=Math.min(rows,region.height()-row);
            ImageRegion chunk=count==region.height()?region:new ImageRegion(region.mipLevel(),region.arrayLayer(),region.x(),region.y()+row,region.width(),count,region.slice());
            int length=Math.addExact(Math.multiplyExact(count-1,stride),tight);
            uploadChunk(target,chunk,source.slice(source.position()+Math.multiplyExact(row,stride),length),pitch,length);
            row+=count;
        }
    }
    private void uploadChunk(GpuImage target,ImageRegion region,ByteBuffer source,int pitch,int bytes){
        if(bytes<=BlazeUploadBatch.MAX_BYTES){batched(target,region,source,pitch,bytes);return;}
        BlazeBufferPool.Slice staging=host.acquire(bytes);
        try{
            staging.write(source.slice(source.position(),bytes));
            ImageCommand command=ImageCommand.copyBufferToImage(context,staging.buffer(),staging.offset(),pitch,target,region);
            ImageOperation operation=new ImageOperation(command,staging,new GpuImage[]{target});staging=null;
            queue.add(operation,bytes);
        }finally{if(staging!=null)staging.close();}
    }
    /** Joins the newest queued operation when it copies into the same image, so an atlas tick costs one command. */
    private void batched(GpuImage target,ImageRegion region,ByteBuffer source,int pitch,int bytes){
        BlazeBufferPool.Slice chunk=uploads.reserve(bytes);
        BlazeUploadBatch batch=queue.last() instanceof BlazeUploadBatch open&&open.accepts(target,chunk,region)?open:null;
        if(batch==null){batch=new BlazeUploadBatch(context,target,chunk,uploadTiles);queue.add(batch,chunk.size());batch.accepts(target,chunk,region);}
        batch.add(uploads.write(source,bytes),pitch,region);
    }
    void clearColor(GpuImage image,int mip,int layer,float r,float g,float b,float a){image(ImageCommand.clearColor(context,image,mip,layer,r,g,b,a),image);}
    void copy(GpuImage source,ImageRegion from,GpuImage target,ImageRegion to){image(ImageCommand.copy(context,source,from,target,to),source,target);}
    void blit(GpuImage source,int[] from,GpuImage target,int[] to,int filter){image(ImageCommand.blit(context,source,from,target,to,filter),source,target);}
    void blit(GpuImage source,ImageRegion sourceLevel,int[] from,GpuImage target,ImageRegion targetLevel,int[] to,int filter){
        image(ImageCommand.blit(context,source,sourceLevel,from,target,targetLevel,to,filter),source,target);
    }
    void mipmaps(GpuImage image,int base,int layer){image(ImageCommand.mipmaps(context,image,base,layer),image);}
    private void image(ImageCommand command,GpuImage... images){queue.add(new ImageOperation(command,null,images),0);}

    private static final class ImageOperation implements BlazeQueue.Operation {
        private final ImageCommand command;
        private final BlazeBufferPool.Slice staging;
        private final GpuImage[] images;
        private boolean closed;
        private ImageOperation(ImageCommand command,BlazeBufferPool.Slice staging,GpuImage[] images){this.command=command;this.staging=staging;this.images=images;}
        @Override public void record(VkCommandBuffer nativeCommand){command.record(nativeCommand);}
        @Override public void submitted(FrameRing.Submission submission){command.complete();for(GpuImage image:images)image.retainUntil(submission);}
        @Override public void close(){if(!closed){closed=true;try{command.close();}finally{if(staging!=null)staging.close();}}}
    }
    /** A copy into a long-lived buffer, which earlier draws may still read and later draws or copies will read. */
    private static final class DeviceCopy implements BlazeQueue.Operation {
        private static final int READERS=VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT|VK_ACCESS_INDEX_READ_BIT;
        private static final int COPIES=VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;
        private static final int STAGES=VK_PIPELINE_STAGE_VERTEX_INPUT_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT;
        private final GpuBuffer source,target;
        private final long sourceOffset,targetOffset,bytes;
        private final BlazeBufferPool.Slice staging;
        private DeviceCopy(GpuBuffer source,long sourceOffset,GpuBuffer target,long targetOffset,long bytes,BlazeBufferPool.Slice staging){
            this.source=source;this.sourceOffset=sourceOffset;this.target=target;this.targetOffset=targetOffset;this.bytes=bytes;this.staging=staging;
        }
        @Override public void record(VkCommandBuffer command){
            try(MemoryStack stack=VulkanStack.push()){
                var before=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(COPIES);
                vkCmdPipelineBarrier(command,STAGES,VK_PIPELINE_STAGE_TRANSFER_BIT,0,before,null,null);
                vkCmdCopyBuffer(command,source.handle(),target.handle(),VkBufferCopy.calloc(1,stack).srcOffset(sourceOffset).dstOffset(targetOffset).size(bytes));
                var after=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(READERS|COPIES);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,STAGES,0,after,null,null);
            }
        }
        @Override public void close(){if(staging!=null)staging.close();}
    }
    private static final class BufferCopy implements BlazeQueue.Operation {
        private final BlazeBufferPool.Slice source,target;
        private final int targetOffset;
        private BufferCopy(BlazeBufferPool.Slice source,BlazeBufferPool.Slice target,int targetOffset){this.source=source;this.target=target;this.targetOffset=targetOffset;}
        @Override public void record(VkCommandBuffer command){
            try(MemoryStack stack=VulkanStack.push()){
                vkCmdCopyBuffer(command,source.buffer().handle(),target.buffer().handle(),VkBufferCopy.calloc(1,stack).srcOffset(source.offset()).dstOffset((long)target.offset()+targetOffset).size(source.size()));
                var barrier=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT|VK_ACCESS_INDEX_READ_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,0,barrier,null,null);
            }
        }
        @Override public void close(){source.close();}
    }
}
