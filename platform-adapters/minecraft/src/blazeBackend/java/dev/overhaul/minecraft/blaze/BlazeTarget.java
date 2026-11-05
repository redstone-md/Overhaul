package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import dev.overhaul.vulkan.ImageFormat;
import dev.overhaul.vulkan.ImmediateCommands;
import dev.overhaul.vulkan.VulkanContext;
import dev.overhaul.vulkan.FrameRing;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;

/** LOAD-preserving framebuffer: repeated GUI batches never clear previous batches. */
public final class BlazeTarget implements AutoCloseable {
    private final VulkanContext context;
    private final int width, height, level;
    private final boolean ownsColor, ownsDepth, borrowsViews;
    private GpuImage color, depth;
    private GpuImageView colorView, depthView;
    private List<GpuImageView> attachments;
    private List<ImageFormat> colorFormats;
    private long renderPass, framebuffer;
    private BlazeQueue queue;
    private boolean closing;

    public BlazeTarget(VulkanContext context, GpuAllocator allocator, ImmediateCommands commands, int width, int height, boolean useDepth) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Empty framebuffer");
        this.context=context;this.width=width;this.height=height;level=0;ownsColor=true;ownsDepth=true;borrowsViews=false;
        try {
            color=allocator.createImage(ImageFormat.RGBA8_UNORM,width,height,1,1,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            colorView=color.view(0,1,0,1);commands.clearColor(color,0,0,0,0,0,0);
            if(useDepth){depth=allocator.createImage(ImageFormat.D32_SFLOAT,width,height,1,1,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                depthView=depth.view(0,1,0,1);commands.clearDepth(depth,0,0,1);}
            createPass();
        } catch(RuntimeException|Error failure){close();throw failure;}
    }

    /**
     * A target over images a mod attached; they stay owned by their textures. Vulkan needs a colour attachment for
     * pipelines to stay compatible, so a depth-only framebuffer gets a private colour image nothing reads.
     */
    BlazeTarget(VulkanContext context, GpuAllocator allocator, BlazeTextures.Attachment colorAttachment, BlazeTextures.Attachment depthAttachment) {
        if (colorAttachment == null && depthAttachment == null) throw new IllegalArgumentException("Framebuffer without attachments");
        this.context=context;ownsColor=colorAttachment==null;ownsDepth=false;borrowsViews=false;
        BlazeTextures.Attachment sized=colorAttachment!=null?colorAttachment:depthAttachment;
        level=sized.mip();
        int fitWidth=sized.image().width(sized.mip()),fitHeight=sized.image().height(sized.mip());
        if(depthAttachment!=null){fitWidth=Math.min(fitWidth,depthAttachment.image().width(depthAttachment.mip()));fitHeight=Math.min(fitHeight,depthAttachment.image().height(depthAttachment.mip()));}
        width=fitWidth;height=fitHeight;
        try {
            color=ownsColor?allocator.createImage(ImageFormat.RGBA8_UNORM,width,height,1,1,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT):colorAttachment.image();
            colorView=ownsColor?color.view(0,1,0,1):color.view(colorAttachment.mip(),1,colorAttachment.layer(),1);
            if(depthAttachment!=null){depth=depthAttachment.image();depthView=depth.view(depthAttachment.mip(),1,depthAttachment.layer(),1);}
            createPass();
        } catch(RuntimeException|Error failure){close();throw failure;}
    }

    /**
     * A target over views somebody else owns, for programs writing several colour outputs. A null view leaves its
     * location unwritten, and a light camera that only writes depth has no colour at all. Colours and depth outlive
     * this target: the owner retires it before closing them.
     */
    BlazeTarget(VulkanContext context, List<GpuImageView> colors, GpuImageView sharedDepth) {
        this.context=context;level=0;ownsColor=false;ownsDepth=false;borrowsViews=true;
        attachments=Collections.unmodifiableList(new ArrayList<>(colors));
        int fitWidth=Integer.MAX_VALUE,fitHeight=Integer.MAX_VALUE;
        for(GpuImageView view:attachments){
            if(view==null)continue;
            if(colorView==null)colorView=view;
            fitWidth=Math.min(fitWidth,view.image().width(view.baseMip()));fitHeight=Math.min(fitHeight,view.image().height(view.baseMip()));
        }
        if(colorView==null&&sharedDepth==null)throw new IllegalArgumentException("Framebuffer without an output");
        color=colorView==null?null:colorView.image();depthView=sharedDepth;depth=sharedDepth==null?null:sharedDepth.image();
        if(depth!=null){fitWidth=Math.min(fitWidth,depth.width(sharedDepth.baseMip()));fitHeight=Math.min(fitHeight,depth.height(sharedDepth.baseMip()));}
        width=fitWidth;height=fitHeight;
        try{createPass();}catch(RuntimeException|Error failure){close();throw failure;}
    }

    private void createPass() {
        if(attachments==null)attachments=Collections.singletonList(colorView);
        ImageFormat[] formats=new ImageFormat[attachments.size()];int written=0;
        for(int index=0;index<formats.length;index++)if(attachments.get(index)!=null){formats[index]=attachments.get(index).image().format();written++;}
        colorFormats=Collections.unmodifiableList(Arrays.asList(formats));
        try(MemoryStack stack=VulkanStack.push()){
            var descriptions=VkAttachmentDescription.calloc(written+(depth==null?0:1),stack);
            var colorReference=VkAttachmentReference.calloc(formats.length,stack);
            LongBuffer views=stack.mallocLong(descriptions.capacity());int next=0;
            for(int index=0;index<formats.length;index++){
                if(formats[index]==null){colorReference.get(index).attachment(VK_ATTACHMENT_UNUSED).layout(VK_IMAGE_LAYOUT_UNDEFINED);continue;}
                descriptions.get(next).format(formats[index].vkFormat()).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                colorReference.get(index).attachment(next).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                views.put(next++,attachments.get(index).handle());
            }
            if(depth!=null)descriptions.get(next).format(depth.format().vkFormat()).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            var subpass=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(formats.length).pColorAttachments(colorReference);
            if(depth!=null){subpass.pDepthStencilAttachment(VkAttachmentReference.calloc(stack).attachment(next).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));views.put(next,depthView.handle());}
            var pointer=stack.mallocLong(1);
            int stages=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            int access=VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            var dependencies=VkSubpassDependency.calloc(2,stack);
            dependencies.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).dstStageMask(stages).srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(access);
            dependencies.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(stages).dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).srcAccessMask(access).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
            check(vkCreateRenderPass(context.device(),VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(descriptions).pSubpasses(subpass).pDependencies(dependencies),null,pointer),"Blaze render pass");renderPass=pointer.get(0);
            check(vkCreateFramebuffer(context.device(),VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass).pAttachments(views).width(width).height(height).layers(1),null,pointer),"Blaze framebuffer");framebuffer=pointer.get(0);
        }
    }

    void attach(BlazeQueue queue){if(this.queue!=null&&this.queue!=queue)throw new IllegalStateException("Framebuffer belongs to another graphics lane");this.queue=queue;}
    void prepare(VkCommandBuffer command,BlazeImageUse images){
        for(int index=0;index<attachments.size();index++){GpuImageView view=attachments.get(index);if(view!=null)images.transition(command,view,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);}
        if(depthView!=null)images.transition(command,depthView,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }
    void begin(VkCommandBuffer command,MemoryStack stack){
        var begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(framebuffer);
        begin.renderArea().extent().set(width,height);vkCmdBeginRenderPass(command,begin,VK_SUBPASS_CONTENTS_INLINE);
    }
    void clear(ImmediateCommands commands,int mask,BlazeDrawState.Snapshot raster,BlazeDrawState.Clear values){
        if(closing)throw new IllegalStateException("Framebuffer is closed");
        if((mask&~(0x4000|0x100|0x400))!=0)throw new UnsupportedOperationException("Unknown framebuffer clear mask");
        boolean maskedColor=(mask&0x4000)!=0&&raster.color().writeMask()!=0;
        boolean depthClear=(mask&0x100)!=0&&depth!=null&&raster.depth().write();
        if(maskedColor&&raster.color().writeMask()!=15)BlazeGlBoundary.reportOnce("masked clear","A colour clear used a partial write mask, which is not lowered; the colour was not cleared");
        boolean colorClear=maskedColor&&raster.color().writeMask()==15;
        int x=raster.scissor()?Math.max(0,raster.sx()):0,y=raster.scissor()?Math.max(0,raster.sy()):0;
        long right=raster.scissor()?Math.min(width,(long)raster.sx()+raster.sw()):width,top=raster.scissor()?Math.min(height,(long)raster.sy()+raster.sh()):height;
        if((!colorClear&&!depthClear)||right<=x||top<=y)return;
        BlazeImageUse images=new BlazeImageUse();
        BlazeQueue.Operation operation=new BlazeQueue.Operation(){
            @Override public void record(VkCommandBuffer command){prepare(command,images);try(MemoryStack stack=VulkanStack.push()){
            begin(command,stack);var clears=VkClearAttachment.calloc((colorClear?1:0)+(depthClear?1:0),stack);int index=0;
            if(colorClear)clears.get(index++).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0).clearValue().color().float32(0,values.r()).float32(1,values.g()).float32(2,values.b()).float32(3,values.a());
            if(depthClear)clears.get(index).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).clearValue().depthStencil().depth(values.depth());
            var rect=VkClearRect.calloc(1,stack).baseArrayLayer(0).layerCount(1);rect.rect().offset().set(x,y);rect.rect().extent().set((int)(right-x),(int)(top-y));
            vkCmdClearAttachments(command,clears,rect);vkCmdEndRenderPass(command);
            }}
            @Override public void submitted(FrameRing.Submission submission){images.submitted(submission);}
            @Override public void close(){images.close();}
        };
        if(queue!=null)queue.add(operation,0);
        else{
            // Compatibility for an externally constructed target before registration.
            try(operation){commands.submit(operation::record);images.complete();}
        }
    }
    public GpuImage color(){return color;}public GpuImage depth(){return depth;}public GpuImageView colorView(){return colorView;}
    public GpuImageView depthView(){return depthView;}
    /** One entry per colour location; null where the location writes nothing. */
    List<ImageFormat> colorFormats(){return colorFormats;}
    /** True when drawing here writes this image, which a draw must then not also sample. */
    boolean writes(GpuImage image){
        if(image==depth)return true;
        for(int index=0;index<attachments.size();index++){GpuImageView view=attachments.get(index);if(view!=null&&view.image()==image)return true;}
        return false;
    }
    public int width(){return width;}public int height(){return height;}int level(){return level;}long renderPass(){return renderPass;}long framebuffer(){return framebuffer;}
    @Override public void close(){
        if(closing)return;closing=true;
        if(queue!=null)queue.retire(this::destroy);else destroy();
    }
    private void destroy(){
        if(framebuffer!=0){vkDestroyFramebuffer(context.device(),framebuffer,null);framebuffer=0;}
        if(renderPass!=0){vkDestroyRenderPass(context.device(),renderPass,null);renderPass=0;}
        if(!borrowsViews){if(depthView!=null)depthView.close();if(colorView!=null)colorView.close();}
        depthView=null;colorView=null;
        if(depth!=null){if(ownsDepth)depth.close();depth=null;}if(color!=null){if(ownsColor)color.close();color=null;}
    }
}
