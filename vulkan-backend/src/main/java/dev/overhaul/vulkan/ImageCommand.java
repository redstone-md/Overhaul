package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One owned, deferred image operation. Endpoints are borrowed until GPU completion;
 * staging and same-subresource copy intermediates belong to this command. Record in
 * queue order, complete after successful submission, close after its fence. Closing
 * an unsubmitted command rolls layout proposals back in reverse order.
 */
public final class ImageCommand implements AutoCloseable {
    @FunctionalInterface private interface Recorder { void record(ImageCommand operation,VkCommandBuffer command); }
    private final VulkanContext context;
    private final Recorder recorder;
    private final List<GpuImage.LayoutChange> changes=new ArrayList<>();
    private final List<AutoCloseable> owned=new ArrayList<>();
    private long stagingBytes;
    private boolean recorded,successful,completed,closed;

    private ImageCommand(VulkanContext context,Recorder recorder) {
        this.context=Objects.requireNonNull(context,"context"); this.recorder=recorder;
    }

    public static ImageCommand upload(VulkanContext context,GpuImage image,ImageRegion region,ByteBuffer pixels,int pitch) {
        validateImage(context,image,region,VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        int bytes=region.byteSize(image,pitch);
        if (Objects.requireNonNull(pixels,"pixels").remaining()<bytes) throw new IllegalArgumentException("Pixel source is shorter than pitched image region");
        GpuBuffer staging=image.allocator().hostBuffer(bytes,VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        try {
            // Snapshot now: callers may recycle or free CPU pixels before queue submission.
            staging.write(pixels.slice(pixels.position(),bytes));
            return copyBufferToImage(context,staging,0,pitch,image,region).own(staging,bytes);
        } catch (RuntimeException|Error failure) { staging.close(); throw failure; }
    }

    public static ImageCommand copyBufferToImage(VulkanContext context,GpuBuffer source,long offset,int pitch,GpuImage target,ImageRegion region) {
        validateImage(context,target,region,VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        validateBuffer(context,source,offset,region.byteSize(target,pitch),VK_BUFFER_USAGE_TRANSFER_SRC_BIT,target);
        return new ImageCommand(context,(operation,command)-> {
            int before=target.layout(region.mipLevel(),region.arrayLayer());
            operation.transition(target,region,command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            try (MemoryStack stack=MemoryStack.stackPush()) {
                VkMemoryBarrier.Buffer host=VkMemoryBarrier.calloc(1,stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT|VK_PIPELINE_STAGE_HOST_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,0,host,null,null);
                vkCmdCopyBufferToImage(command,source.handle(),target.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        copyRegion(target,region,offset,pitch,stack));
            }
            operation.transition(target,region,command,finish(target,before));
        });
    }

    public static ImageCommand copyImageToBuffer(VulkanContext context,GpuImage source,ImageRegion region,GpuBuffer target,long offset,int pitch) {
        validateImage(context,source,region,VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        validateBuffer(context,target,offset,region.byteSize(source,pitch),VK_BUFFER_USAGE_TRANSFER_DST_BIT,source);
        return new ImageCommand(context,(operation,command)-> {
            requireDefined(source,region);
            int before=source.layout(region.mipLevel(),region.arrayLayer());
            operation.transition(source,region,command,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            try (MemoryStack stack=MemoryStack.stackPush()) {
                vkCmdCopyImageToBuffer(command,source.handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,target.handle(),
                        copyRegion(source,region,offset,pitch,stack));
                VkMemoryBarrier.Buffer host=VkMemoryBarrier.calloc(1,stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_HOST_READ_BIT|VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT|VK_PIPELINE_STAGE_HOST_BIT,0,host,null,null);
            }
            operation.transition(source,region,command,before);
        });
    }

    public static ImageCommand copy(VulkanContext context,GpuImage source,ImageRegion from,GpuImage target,ImageRegion to) {
        validateImage(context,source,from,VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        validateImage(context,target,to,VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        if (source.format()!=target.format() || from.width()!=to.width() || from.height()!=to.height()) throw new IllegalArgumentException("Image copy formats/extents differ");
        if (source==target && from.mipLevel()==to.mipLevel() && from.arrayLayer()==to.arrayLayer()) {
            GpuImage intermediate=source.allocator().createImage(source.format(),from.width(),from.height(),1,1,
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            try {
                ImageRegion whole=ImageRegion.whole(intermediate,0,0);
                return new ImageCommand(context,(operation,command)-> {
                    operation.copy(command,source,from,intermediate,whole);
                    operation.copy(command,intermediate,whole,target,to);
                }).own(intermediate,whole.byteSize(intermediate,0));
            } catch (RuntimeException|Error failure) { intermediate.close(); throw failure; }
        }
        return new ImageCommand(context,(operation,command)->operation.copy(command,source,from,target,to));
    }

    private void copy(VkCommandBuffer command,GpuImage source,ImageRegion from,GpuImage target,ImageRegion to) {
        requireDefined(source,from);
        int sourceBefore=source.layout(from.mipLevel(),from.arrayLayer()),targetBefore=target.layout(to.mipLevel(),to.arrayLayer());
        transition(source,from,command,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        transition(target,to,command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack=MemoryStack.stackPush()) {
            VkImageCopy.Buffer copy=VkImageCopy.calloc(1,stack);
            copy.srcSubresource().aspectMask(source.format().aspectMask()).mipLevel(from.mipLevel()).baseArrayLayer(from.arrayLayer()).layerCount(1);
            copy.dstSubresource().aspectMask(target.format().aspectMask()).mipLevel(to.mipLevel()).baseArrayLayer(to.arrayLayer()).layerCount(1);
            copy.srcOffset().set(from.x(),from.y(),0); copy.dstOffset().set(to.x(),to.y(),0); copy.extent().set(from.width(),from.height(),1);
            vkCmdCopyImage(command,source.handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,target.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy);
        }
        transition(source,from,command,sourceBefore);
        transition(target,to,command,finish(target,targetBefore));
    }

    public static ImageCommand clearColor(VulkanContext context,GpuImage image,int mip,int layer,float r,float g,float b,float a) {
        return clear(context,image,mip,layer,r,g,b,a,false,0);
    }
    public static ImageCommand clearDepth(VulkanContext context,GpuImage image,int mip,int layer,float depth,int stencil) {
        return clear(context,image,mip,layer,depth,0,0,0,true,stencil);
    }
    private static ImageCommand clear(VulkanContext context,GpuImage image,int mip,int layer,float r,float g,float b,float a,boolean depth,int stencil) {
        ImageRegion region=ImageRegion.whole(image,mip,layer);
        requireContext(context,image); image.checkSubresource(mip,layer); image.requireUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        if (depth!=(image.format().aspectMask()!=VK_IMAGE_ASPECT_COLOR_BIT)) throw new IllegalArgumentException("Clear type differs from format");
        if (!Float.isFinite(r)||!Float.isFinite(g)||!Float.isFinite(b)||!Float.isFinite(a)||depth&&(r<0||r>1)) throw new IllegalArgumentException("Invalid clear value");
        return new ImageCommand(context,(operation,command)-> {
            int before=image.layout(mip,layer);
            operation.transition(image,region,command,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            try (MemoryStack stack=MemoryStack.stackPush()) {
                VkImageSubresourceRange.Buffer range=VkImageSubresourceRange.calloc(1,stack).aspectMask(image.format().aspectMask())
                        .baseMipLevel(mip).levelCount(1).baseArrayLayer(layer).layerCount(1);
                if (depth) vkCmdClearDepthStencilImage(command,image.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VkClearDepthStencilValue.calloc(stack).depth(r).stencil(stencil),range);
                else {
                    VkClearColorValue color=VkClearColorValue.calloc(stack);
                    if (image.format().name().endsWith("UINT")) color.uint32(0,(int)r).uint32(1,(int)g).uint32(2,(int)b).uint32(3,(int)a);
                    else if (image.format().name().endsWith("SINT")) color.int32(0,(int)r).int32(1,(int)g).int32(2,(int)b).int32(3,(int)a);
                    else color.float32(0,r).float32(1,g).float32(2,b).float32(3,a);
                    vkCmdClearColorImage(command,image.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,color,range);
                }
            }
            operation.transition(image,region,command,finish(image,before));
        });
    }

    public static ImageCommand transition(VulkanContext context,GpuImage image,int mip,int layer,int layout) {
        requireContext(context,image); image.checkSubresource(mip,layer); image.validateLayout(layout);
        return new ImageCommand(context,(operation,command)->operation.changes.add(image.recordTransition(command,mip,1,layer,1,layout)));
    }

    public void record(VkCommandBuffer command) {
        requireOpen();
        if (recorded) throw new IllegalStateException("Image command was already recorded");
        if (command.getDevice()!=context.device()) throw new IllegalArgumentException("Command belongs to another device");
        recorded=true; recorder.record(this,command); successful=true;
    }
    /** CPU layout tracking now represents commands accepted into the ordered GPU queue. */
    public void complete() {
        requireOpen();
        if (!successful) throw new IllegalStateException("Cannot commit an unsuccessfully recorded image command");
        if (completed) return;
        completed=true;
        var iterator=changes.iterator();
        while(iterator.hasNext()){GpuImage.LayoutChange change=iterator.next();change.commit();change.close();iterator.remove();}
        // Rollback state is CPU-only. Discard it before an independent completed fence
        // can destroy an endpoint; owned native staging/intermediates remain fence-owned.
        changes.clear();
    }
    public long stagingBytes() { return stagingBytes; }

    private ImageCommand own(AutoCloseable resource,long bytes) { owned.add(resource); stagingBytes=Math.addExact(stagingBytes,bytes); return this; }
    private void transition(GpuImage image,ImageRegion region,VkCommandBuffer command,int layout) {
        changes.add(image.recordTransition(command,region.mipLevel(),1,region.arrayLayer(),1,layout));
    }
    private static int finish(GpuImage image,int previous) {
        if (previous!=VK_IMAGE_LAYOUT_UNDEFINED) return previous;
        if ((image.usage()&VK_IMAGE_USAGE_SAMPLED_BIT)!=0) return VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        if ((image.usage()&VK_IMAGE_USAGE_STORAGE_BIT)!=0) return VK_IMAGE_LAYOUT_GENERAL;
        if ((image.usage()&VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)!=0) return VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        if ((image.usage()&VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)!=0) return VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        return VK_IMAGE_LAYOUT_GENERAL;
    }
    static void validateImage(VulkanContext context,GpuImage image,ImageRegion region,int usage) {
        requireContext(context,image); region.validate(image); image.requireUsage(usage);
        if (Integer.bitCount(image.format().aspectMask())!=1) throw new IllegalArgumentException("Combined depth/stencil transfers require an explicit single aspect");
    }
    private static void requireContext(VulkanContext context,GpuImage image) {
        if (image.context()!=context) throw new IllegalArgumentException("Image belongs to another Vulkan context");
    }
    private static void requireDefined(GpuImage image,ImageRegion region) {
        if (image.layout(region.mipLevel(),region.arrayLayer())==VK_IMAGE_LAYOUT_UNDEFINED) throw new IllegalStateException("Image subresource has not been initialized");
    }
    private static void validateBuffer(VulkanContext context,GpuBuffer buffer,long offset,int bytes,int usage,GpuImage image) {
        if (buffer.context()!=context || (buffer.usage()&usage)!=usage) throw new IllegalArgumentException("Buffer owner/transfer usage differs");
        if (offset<0 || offset>buffer.size()-(long)bytes || offset%image.format().bytesPerPixel()!=0
                || image.format().aspectMask()!=VK_IMAGE_ASPECT_COLOR_BIT && offset%4!=0) throw new IllegalArgumentException("Buffer image copy range/alignment is invalid");
    }
    private static VkBufferImageCopy.Buffer copyRegion(GpuImage image,ImageRegion region,long offset,int pitch,MemoryStack stack) {
        VkBufferImageCopy.Buffer copy=VkBufferImageCopy.calloc(1,stack).bufferOffset(offset)
                .bufferRowLength(region.rowPitch(image,pitch)/image.format().bytesPerPixel());
        copy.imageSubresource().aspectMask(image.format().aspectMask()).mipLevel(region.mipLevel()).baseArrayLayer(region.arrayLayer()).layerCount(1);
        copy.imageOffset().set(region.x(),region.y(),0); copy.imageExtent().set(region.width(),region.height(),1); return copy;
    }
    private void requireOpen() { context.device(); if (closed) throw new IllegalStateException("Image command is closed"); }
    @Override public void close() {
        if (closed) return;
        requireOpen(); closed=true;
        RuntimeException failure=null;
        for (int index=changes.size()-1;index>=0;index--) {
            try { if(completed)changes.get(index).commit(); changes.get(index).close(); }
            catch (RuntimeException error) { if(failure==null)failure=error;else failure.addSuppressed(error); }
        }
        for (int index=owned.size()-1;index>=0;index--) {
            try { owned.get(index).close(); }
            catch (Exception error) { if(failure==null)failure=new IllegalStateException("Cannot retire image command storage",error);else failure.addSuppressed(error); }
        }
        changes.clear(); owned.clear();
        if (failure!=null) throw failure;
    }
}
