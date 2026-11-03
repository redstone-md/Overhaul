package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImmediateCommands;
import dev.overhaul.vulkan.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/** LOAD-preserving framebuffer: repeated GUI batches never clear previous batches. */
public final class BlazeTarget implements AutoCloseable {
    private final VulkanContext context;
    private final int width, height;
    private GpuImage color, depth;
    private GpuImageView colorView, depthView;
    private long renderPass, framebuffer;

    public BlazeTarget(VulkanContext context, GpuAllocator allocator, ImmediateCommands commands, int width, int height, boolean useDepth) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Empty framebuffer");
        this.context=context;this.width=width;this.height=height;
        try {
            color=allocator.createImage(ImageFormat.RGBA8_UNORM,width,height,1,1,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            colorView=color.view(0,1,0,1);commands.clearColor(color,0,0,0,0,0,0);
            if(useDepth){depth=allocator.createImage(ImageFormat.D32_SFLOAT,width,height,1,1,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                depthView=depth.view(0,1,0,1);commands.clearDepth(depth,0,0,1);}
            createPass();
        } catch(RuntimeException|Error failure){close();throw failure;}
    }

    private void createPass() {
        try(MemoryStack stack=MemoryStack.stackPush()){
            var attachments=VkAttachmentDescription.calloc(depth==null?1:2,stack);
            attachments.get(0).format(color.format().vkFormat()).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            if(depth!=null)attachments.get(1).format(depth.format().vkFormat()).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            var colorReference=VkAttachmentReference.calloc(1,stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            var subpass=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(colorReference);
            if(depth!=null)subpass.pDepthStencilAttachment(VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
            var pointer=stack.mallocLong(1);
            int stages=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            int access=VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            var dependencies=VkSubpassDependency.calloc(2,stack);
            dependencies.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).dstStageMask(stages).srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(access);
            dependencies.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(stages).dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).srcAccessMask(access).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
            check(vkCreateRenderPass(context.device(),VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachments).pSubpasses(subpass).pDependencies(dependencies),null,pointer),"Blaze render pass");renderPass=pointer.get(0);
            var views=depth==null?stack.longs(colorView.handle()):stack.longs(colorView.handle(),depthView.handle());
            check(vkCreateFramebuffer(context.device(),VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass).pAttachments(views).width(width).height(height).layers(1),null,pointer),"Blaze framebuffer");framebuffer=pointer.get(0);
        }
    }

    void prepare(ImmediateCommands commands){commands.transition(color,0,0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);if(depth!=null)commands.transition(depth,0,0,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);}
    void begin(VkCommandBuffer command,MemoryStack stack){
        var begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(framebuffer);
        begin.renderArea().extent().set(width,height);vkCmdBeginRenderPass(command,begin,VK_SUBPASS_CONTENTS_INLINE);
    }
    void clear(ImmediateCommands commands,int mask,BlazeDrawState.Snapshot raster,BlazeDrawState.Clear values){
        if((mask&~(0x4000|0x100|0x400))!=0)throw new UnsupportedOperationException("Unknown framebuffer clear mask");
        boolean colorClear=(mask&0x4000)!=0&&raster.color().writeMask()!=0;
        boolean depthClear=(mask&0x100)!=0&&depth!=null&&raster.depth().write();
        if(colorClear&&raster.color().writeMask()!=15)throw new UnsupportedOperationException("Masked color clear requires explicit component lowering");
        int x=raster.scissor()?Math.max(0,raster.sx()):0,y=raster.scissor()?Math.max(0,raster.sy()):0;
        long right=raster.scissor()?Math.min(width,(long)raster.sx()+raster.sw()):width,top=raster.scissor()?Math.min(height,(long)raster.sy()+raster.sh()):height;
        if((!colorClear&&!depthClear)||right<=x||top<=y)return;
        prepare(commands);
        commands.submit(command->{try(MemoryStack stack=MemoryStack.stackPush()){
            begin(command,stack);var clears=VkClearAttachment.calloc((colorClear?1:0)+(depthClear?1:0),stack);int index=0;
            if(colorClear)clears.get(index++).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0).clearValue().color().float32(0,values.r()).float32(1,values.g()).float32(2,values.b()).float32(3,values.a());
            if(depthClear)clears.get(index).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).clearValue().depthStencil().depth(values.depth());
            var rect=VkClearRect.calloc(1,stack).baseArrayLayer(0).layerCount(1);rect.rect().offset().set(x,y);rect.rect().extent().set((int)(right-x),(int)(top-y));
            vkCmdClearAttachments(command,clears,rect);vkCmdEndRenderPass(command);
        }});
    }
    public GpuImage color(){return color;}public GpuImage depth(){return depth;}public GpuImageView colorView(){return colorView;}
    public GpuImageView depthView(){return depthView;}
    public int width(){return width;}public int height(){return height;}long renderPass(){return renderPass;}long framebuffer(){return framebuffer;}
    @Override public void close(){
        if(framebuffer!=0){vkDestroyFramebuffer(context.device(),framebuffer,null);framebuffer=0;}
        if(renderPass!=0){vkDestroyRenderPass(context.device(),renderPass,null);renderPass=0;}
        if(depthView!=null){depthView.close();depthView=null;}if(colorView!=null){colorView.close();colorView=null;}
        if(depth!=null){depth.close();depth=null;}if(color!=null){color.close();color=null;}
    }
}
