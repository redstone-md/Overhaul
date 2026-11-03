package dev.overhaul.vulkan;

import java.nio.ByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Synchronous facade over the same owned operations used by asynchronous encoders. */
final class ImageTransfers {
    private final ImmediateCommands commands;
    private final VulkanContext context;
    ImageTransfers(ImmediateCommands commands,VulkanContext context) { this.commands=commands; this.context=context; }

    void upload(GpuImage image,ImageRegion region,ByteBuffer pixels,int pitch) {
        execute(ImageCommand.upload(context,image,region,pixels,pitch));
    }
    ByteBuffer readback(GpuImage image,ImageRegion region,int pitch) {
        ImageCommand.validateImage(context,image,region,VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        int bytes=region.byteSize(image,pitch);
        try (GpuBuffer target=image.allocator().hostBuffer(bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            target.write(ByteBuffer.allocate(bytes));
            copyImageToBuffer(image,region,target,0,pitch);
            return target.read();
        }
    }
    void copyBufferToImage(GpuBuffer source,long offset,int pitch,GpuImage target,ImageRegion region) {
        execute(ImageCommand.copyBufferToImage(context,source,offset,pitch,target,region));
    }
    void copyImageToBuffer(GpuImage source,ImageRegion region,GpuBuffer target,long offset,int pitch) {
        execute(ImageCommand.copyImageToBuffer(context,source,region,target,offset,pitch));
    }
    void copy(GpuImage source,ImageRegion from,GpuImage target,ImageRegion to) {
        execute(ImageCommand.copy(context,source,from,target,to));
    }
    void clear(GpuImage image,int mip,int layer,float r,float g,float b,float a,boolean depth,int stencil) {
        execute(depth ? ImageCommand.clearDepth(context,image,mip,layer,r,stencil)
                : ImageCommand.clearColor(context,image,mip,layer,r,g,b,a));
    }
    void transition(GpuImage image,int mip,int layer,int layout) {
        execute(ImageCommand.transition(context,image,mip,layer,layout));
    }
    private void execute(ImageCommand command) {
        try (command) { commands.submit(command::record); command.complete(); }
    }
}
