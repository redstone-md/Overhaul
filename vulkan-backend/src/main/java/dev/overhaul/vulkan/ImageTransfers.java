package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import static org.lwjgl.vulkan.VK10.*;

/** Synchronous image operations share checked subresources, row addressing and transactional layout barriers. */
final class ImageTransfers {
    private final ImmediateCommands commands;
    private final VulkanContext context;
    ImageTransfers(ImmediateCommands commands,VulkanContext context){this.commands=commands;this.context=context;}

    void upload(GpuImage image,ImageRegion region,ByteBuffer pixels,int pitch){
        validate(image,region,VK_IMAGE_USAGE_TRANSFER_DST_BIT);int bytes=region.byteSize(image,pitch);
        if(pixels.remaining()<bytes)throw new IllegalArgumentException("pixel source is shorter than pitched image region");
        try(GpuBuffer staging=image.allocator().hostBuffer(bytes,VK_BUFFER_USAGE_TRANSFER_SRC_BIT)){
            staging.write(pixels.slice(pixels.position(),bytes));copyBufferToImage(staging,0,pitch,image,region);
        }
    }
    ByteBuffer readback(GpuImage image,ImageRegion region,int pitch){
        validate(image,region,VK_IMAGE_USAGE_TRANSFER_SRC_BIT);int bytes=region.byteSize(image,pitch);
        try(GpuBuffer target=image.allocator().hostBuffer(bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT)){
            target.write(ByteBuffer.allocate(bytes)); // Padding is never uninitialized native memory.
            copyImageToBuffer(image,region,target,0,pitch);return target.read();
        }
    }
    void copyBufferToImage(GpuBuffer source,long offset,int pitch,GpuImage image,ImageRegion region){
        validate(image,region,VK_IMAGE_USAGE_TRANSFER_DST_BIT);validateBuffer(source,offset,region.byteSize(image,pitch),VK_BUFFER_USAGE_TRANSFER_SRC_BIT,image);
        execute((command,changes)->{
            int before=image.layout(region.mipLevel(),region.arrayLayer());transition(changes,image,region,command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            try(MemoryStack stack=MemoryStack.stackPush()){
                VkMemoryBarrier.Buffer host=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT|VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,host,null,null);
                vkCmdCopyBufferToImage(command,source.handle(),image.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copyRegion(image,region,offset,pitch,stack));
            }
            transition(changes,image,region,command,finish(image,before));
        });
    }
    void copyImageToBuffer(GpuImage image,ImageRegion region,GpuBuffer target,long offset,int pitch){
        validate(image,region,VK_IMAGE_USAGE_TRANSFER_SRC_BIT);requireDefined(image,region);
        validateBuffer(target,offset,region.byteSize(image,pitch),VK_BUFFER_USAGE_TRANSFER_DST_BIT,image);
        execute((command,changes)->{
            int before=image.layout(region.mipLevel(),region.arrayLayer());transition(changes,image,region,command,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            try(MemoryStack stack=MemoryStack.stackPush()){
                vkCmdCopyImageToBuffer(command,image.handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,target.handle(),copyRegion(image,region,offset,pitch,stack));
                VkMemoryBarrier.Buffer host=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT|VK_ACCESS_MEMORY_READ_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT|VK_PIPELINE_STAGE_HOST_BIT,0,host,null,null);
            }
            transition(changes,image,region,command,before);
        });
    }
    void copy(GpuImage source,ImageRegion from,GpuImage target,ImageRegion to){
        validate(source,from,VK_IMAGE_USAGE_TRANSFER_SRC_BIT);validate(target,to,VK_IMAGE_USAGE_TRANSFER_DST_BIT);requireDefined(source,from);
        if(source.format()!=target.format()||from.width()!=to.width()||from.height()!=to.height())throw new IllegalArgumentException("image copy formats/extents differ");
        if(source==target&&from.mipLevel()==to.mipLevel()&&from.arrayLayer()==to.arrayLayer()){
            try(GpuImage intermediate=source.allocator().createImage(source.format(),from.width(),from.height(),1,1,VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT)){
                ImageRegion whole=ImageRegion.whole(intermediate,0,0);copy(source,from,intermediate,whole);copy(intermediate,whole,target,to);
            }
            return;
        }
        execute((command,changes)->{
            int sourceBefore=source.layout(from.mipLevel(),from.arrayLayer()),targetBefore=target.layout(to.mipLevel(),to.arrayLayer());
            transition(changes,source,from,command,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);transition(changes,target,to,command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            try(MemoryStack stack=MemoryStack.stackPush()){
                VkImageCopy.Buffer copy=VkImageCopy.calloc(1,stack);
                copy.srcSubresource().aspectMask(source.format().aspectMask()).mipLevel(from.mipLevel()).baseArrayLayer(from.arrayLayer()).layerCount(1);
                copy.dstSubresource().aspectMask(target.format().aspectMask()).mipLevel(to.mipLevel()).baseArrayLayer(to.arrayLayer()).layerCount(1);
                copy.srcOffset().set(from.x(),from.y(),0);copy.dstOffset().set(to.x(),to.y(),0);copy.extent().set(from.width(),from.height(),1);
                vkCmdCopyImage(command,source.handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,target.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy);
            }
            transition(changes,source,from,command,sourceBefore);transition(changes,target,to,command,finish(target,targetBefore));
        });
    }
    void clear(GpuImage image,int mip,int layer,float r,float g,float b,float a,boolean depth,int stencil){
        ImageRegion region=ImageRegion.whole(image,mip,layer);image.requireUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT);requireContext(image);
        if(depth!=(image.format().aspectMask()!=VK_IMAGE_ASPECT_COLOR_BIT))throw new IllegalArgumentException("clear type differs from format");
        if(!Float.isFinite(r)||!Float.isFinite(g)||!Float.isFinite(b)||!Float.isFinite(a)||depth&&(r<0||r>1))throw new IllegalArgumentException("invalid clear value");
        execute((command,changes)->{
            int before=image.layout(mip,layer);transition(changes,image,region,command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            try(MemoryStack stack=MemoryStack.stackPush()){
                VkImageSubresourceRange.Buffer range=VkImageSubresourceRange.calloc(1,stack).aspectMask(image.format().aspectMask()).baseMipLevel(mip).levelCount(1).baseArrayLayer(layer).layerCount(1);
                if(depth)vkCmdClearDepthStencilImage(command,image.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkClearDepthStencilValue.calloc(stack).depth(r).stencil(stencil),range);
                else {
                    VkClearColorValue color=VkClearColorValue.calloc(stack);
                    if(image.format().name().endsWith("UINT"))color.uint32(0,(int)r).uint32(1,(int)g).uint32(2,(int)b).uint32(3,(int)a);
                    else if(image.format().name().endsWith("SINT"))color.int32(0,(int)r).int32(1,(int)g).int32(2,(int)b).int32(3,(int)a);
                    else color.float32(0,r).float32(1,g).float32(2,b).float32(3,a);
                    vkCmdClearColorImage(command,image.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,color,range);
                }
            }
            transition(changes,image,region,command,finish(image,before));
        });
    }
    void transition(GpuImage image,int mip,int layer,int layout){
        requireContext(image);image.checkSubresource(mip,layer);
        execute((command,changes)->changes.add(image.recordTransition(command,mip,1,layer,1,layout)));
    }
    private void execute(BiConsumer<VkCommandBuffer,List<GpuImage.LayoutChange>> record){
        List<GpuImage.LayoutChange> changes=new ArrayList<>();
        try{commands.submit(command->record.accept(command,changes));for(GpuImage.LayoutChange change:changes)change.commit();}
        finally{for(int i=changes.size()-1;i>=0;i--)changes.get(i).close();}
    }
    private static void transition(List<GpuImage.LayoutChange> changes,GpuImage image,ImageRegion region,VkCommandBuffer command,int layout){
        changes.add(image.recordTransition(command,region.mipLevel(),1,region.arrayLayer(),1,layout));
    }
    private static int finish(GpuImage image,int previous){
        if(previous!=VK_IMAGE_LAYOUT_UNDEFINED)return previous;
        if((image.usage()&VK_IMAGE_USAGE_SAMPLED_BIT)!=0)return VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        if((image.usage()&VK_IMAGE_USAGE_STORAGE_BIT)!=0)return VK_IMAGE_LAYOUT_GENERAL;
        if((image.usage()&VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)!=0)return VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        if((image.usage()&VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)!=0)return VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        return VK_IMAGE_LAYOUT_GENERAL;
    }
    private void requireContext(GpuImage image){if(image.context()!=context)throw new IllegalArgumentException("image belongs to another Vulkan context");}
    private void validate(GpuImage image,ImageRegion region,int usage){requireContext(image);region.validate(image);image.requireUsage(usage);
        if(Integer.bitCount(image.format().aspectMask())!=1)throw new IllegalArgumentException("combined depth/stencil transfers require an explicit single aspect");}
    private static void requireDefined(GpuImage image,ImageRegion region){if(image.layout(region.mipLevel(),region.arrayLayer())==VK_IMAGE_LAYOUT_UNDEFINED)throw new IllegalStateException("image subresource has not been initialized");}
    private void validateBuffer(GpuBuffer buffer,long offset,int bytes,int usage,GpuImage image){
        if(buffer.context()!=context||(buffer.usage()&usage)!=usage)throw new IllegalArgumentException("buffer owner/transfer usage differs");
        if(offset<0||offset>buffer.size()-(long)bytes||offset%image.format().bytesPerPixel()!=0
            ||image.format().aspectMask()!=VK_IMAGE_ASPECT_COLOR_BIT&&offset%4!=0)throw new IllegalArgumentException("buffer image copy range/alignment is invalid");
    }
    private static VkBufferImageCopy.Buffer copyRegion(GpuImage image,ImageRegion region,long offset,int pitch,MemoryStack stack){
        VkBufferImageCopy.Buffer copy=VkBufferImageCopy.calloc(1,stack).bufferOffset(offset).bufferRowLength(region.rowPitch(image,pitch)/image.format().bytesPerPixel());
        copy.imageSubresource().aspectMask(image.format().aspectMask()).mipLevel(region.mipLevel()).baseArrayLayer(region.arrayLayer()).layerCount(1);
        copy.imageOffset().set(region.x(),region.y(),0);copy.imageExtent().set(region.width(),region.height(),1);return copy;
    }
}
