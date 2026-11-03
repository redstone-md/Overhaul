package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import dev.overhaul.vulkan.GpuImage;
import dev.overhaul.vulkan.GpuImageView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Attachment views, load/clear state and native render-pass lifetime belong to one submission. */
final class PassAttachments implements AutoCloseable {
    private record Entry(GpuImageView view,boolean depth,float[] color,Double clearDepth) {}
    private final NativePassResources resources;
    private final List<Entry> entries=new ArrayList<>();
    private final List<GpuImageView> framebufferViews=new ArrayList<>();
    private final List<Integer> colorFormats=new ArrayList<>();
    private final int[] colorReferences;
    private int depthReference=VK_ATTACHMENT_UNUSED;
    private int depthFormat=VK_FORMAT_UNDEFINED;
    final int width,height,x,y,areaWidth,areaHeight;
    long renderPass,framebuffer;

    PassAttachments(NativePassResources resources,RenderPassDescriptor descriptor,Consumer<Object> retain) {
        this.resources=resources;
        int width=0,height=0;
        colorReferences=new int[descriptor.colorAttachments().size()];
        for(int slot=0;slot<colorReferences.length;slot++) {
            var attachment=descriptor.colorAttachments().get(slot);
            if(attachment==null){colorReferences[slot]=VK_ATTACHMENT_UNUSED;colorFormats.add(VK_FORMAT_UNDEFINED);continue;}
            retain.accept(attachment.textureView());GpuImageView view=resources.texture(attachment.textureView());
            if(view.layerCount()!=1)throw new UnsupportedOperationException("Layered color attachments require an explicit layer view");
            float[] clear=attachment.clearValue().map(value->new float[]{value.x(),value.y(),value.z(),value.w()}).orElse(null);
            colorReferences[slot]=entries.size();entries.add(new Entry(view,false,clear,null));colorFormats.add(view.image().format().vkFormat());
            int w=view.image().width(view.baseMip()),h=view.image().height(view.baseMip());
            if(width!=0&&(width!=w||height!=h))throw new IllegalArgumentException("Mismatched render attachment extents");width=w;height=h;
        }
        var depth=descriptor.depthAttachment();
        if(depth!=null){
            retain.accept(depth.textureView());GpuImageView view=resources.texture(depth.textureView());
            if(view.layerCount()!=1)throw new UnsupportedOperationException("Layered depth attachments require an explicit layer view");
            depthReference=entries.size();depthFormat=view.image().format().vkFormat();
            entries.add(new Entry(view,true,null,depth.clearValue().isPresent()?depth.clearValue().getAsDouble():null));
            int w=view.image().width(view.baseMip()),h=view.image().height(view.baseMip());
            if(width!=0&&(width!=w||height!=h))throw new IllegalArgumentException("Mismatched depth attachment extent");width=w;height=h;
        }
        var area=descriptor.renderArea();
        if(width==0&&area!=null){width=area.x()+area.width();height=area.y()+area.height();}
        if(width<1||height<1)throw new IllegalArgumentException("A render pass needs attachments or a positive render area");
        this.width=width;this.height=height;x=area==null?0:area.x();y=area==null?0:area.y();areaWidth=area==null?width:area.width();areaHeight=area==null?height:area.height();
        if(x<0||y<0||areaWidth<1||areaHeight<1||(long)x+areaWidth>width||(long)y+areaHeight>height)throw new IllegalArgumentException("Render area outside attachment bounds");
    }
    List<Integer> colorFormats(){return List.copyOf(colorFormats);}
    int depthFormat(){return depthFormat;}
    boolean overlaps(GpuImageView sampled){
        for(Entry entry:entries){var target=entry.view();
            if(target.image()==sampled.image()&&target.baseMip()>=sampled.baseMip()&&target.baseMip()<sampled.baseMip()+sampled.mipCount()
                    &&target.baseLayer()>=sampled.baseLayer()&&target.baseLayer()<sampled.baseLayer()+sampled.layerCount())return true;
        }return false;
    }
    void begin(VkCommandBuffer command,List<GpuImage.LayoutChange> transitions) {
        create();
        for(Entry entry:entries){GpuImageView view=entry.view();transitions.add(view.image().recordTransition(command,view.baseMip(),1,view.baseLayer(),1,
                entry.depth()?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));}
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkClearValue.Buffer clear=VkClearValue.calloc(entries.size(),stack);
            for(int index=0;index<entries.size();index++){Entry entry=entries.get(index);
                if(entry.depth())clear.get(index).depthStencil().depth(entry.clearDepth()==null?1:entry.clearDepth().floatValue()).stencil(0);
                else if(entry.color()!=null)for(int channel=0;channel<4;channel++)clear.get(index).color().float32(channel,entry.color()[channel]);
            }
            VkRenderPassBeginInfo begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(framebuffer).pClearValues(clear);
            begin.renderArea().offset().set(x,y);begin.renderArea().extent().set(areaWidth,areaHeight);
            vkCmdBeginRenderPass(command,begin,VK_SUBPASS_CONTENTS_INLINE);
            vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).width(width).height(height).minDepth(0).maxDepth(1));
        }
    }
    private void create(){
        if(renderPass!=0)return;
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkAttachmentDescription.Buffer attachments=VkAttachmentDescription.calloc(entries.size(),stack);
            LongBuffer views=stack.mallocLong(entries.size());
            for(int index=0;index<entries.size();index++){
                Entry entry=entries.get(index);GpuImageView view=entry.view();
                GpuImageView attachmentView=view.image().view(view.baseMip(),1,view.baseLayer(),1,VK_IMAGE_VIEW_TYPE_2D,view.image().format().aspectMask());
                framebufferViews.add(attachmentView);views.put(attachmentView.handle());int layout=entry.depth()?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                attachments.get(index).format(view.image().format().vkFormat()).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(entry.depth()?(entry.clearDepth()!=null?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD):(entry.color()!=null?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD))
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD).stencilStoreOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .initialLayout(layout).finalLayout(layout);
            }views.flip();
            VkAttachmentReference.Buffer colors=VkAttachmentReference.calloc(colorReferences.length,stack);
            for(int slot=0;slot<colorReferences.length;slot++)colors.get(slot).attachment(colorReferences[slot]).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(colors.remaining()).pColorAttachments(colors);
            if(depthReference!=VK_ATTACHMENT_UNUSED)subpass.pDepthStencilAttachment(VkAttachmentReference.calloc(stack).attachment(depthReference).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
            VkSubpassDependency.Buffer dependencies=VkSubpassDependency.calloc(2,stack);
            int stages=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            int access=VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            dependencies.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).dstStageMask(stages).srcAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(access);
            dependencies.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(stages).dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).srcAccessMask(access).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateRenderPass(resources.context().device(),VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachments).pSubpasses(subpass).pDependencies(dependencies),null,pointer),"overhaul render pass");renderPass=pointer.get(0);
            check(vkCreateFramebuffer(resources.context().device(),VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass).pAttachments(views).width(width).height(height).layers(1),null,pointer),"overhaul framebuffer");framebuffer=pointer.get(0);
        }catch(RuntimeException|Error failure){close();throw failure;}
    }
    @Override public void close(){if(framebuffer!=0)vkDestroyFramebuffer(resources.context().device(),framebuffer,null);if(renderPass!=0)vkDestroyRenderPass(resources.context().device(),renderPass,null);framebuffer=renderPass=0;for(var view:framebufferViews)view.close();framebufferViews.clear();}
}
