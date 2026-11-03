package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.GpuImageView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Owns one framebuffer, compatible render pass, private views, and source-resource pins. */
final class AttachmentBundle implements AutoCloseable {
    private final AttachmentPool pool;
    private final OverhaulDeviceBackend owner;
    private final AttachmentKey key;
    private final List<OverhaulTextureView> sources;
    private final List<GpuImageView> views=new ArrayList<>();
    private final BackendPins pins;
    private long renderPass,framebuffer;
    private boolean closed;

    AttachmentBundle(AttachmentPool pool,OverhaulDeviceBackend owner,AttachmentKey key,List<OverhaulTextureView> sources) {
        this.pool=pool;this.owner=owner;this.key=key;this.sources=List.copyOf(sources);
        pins=new BackendPins(owner,sources.toArray());
    }
    AttachmentKey key(){return key;}
    long renderPass(){return renderPass;}
    long framebuffer(){return framebuffer;}
    boolean references(BackendResource resource){
        for(var source:sources)if(source==resource||source.texture()==resource)return true;return false;
    }
    boolean sourcesOpen(){for(var source:sources)if(source.isClosed()||((BackendResource)source.texture()).isClosed())return false;return true;}
    void create(){
        if(closed)throw new IllegalStateException("Attachment bundle is closed");
        if(renderPass!=0)return;
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkAttachmentDescription.Buffer attachments=VkAttachmentDescription.calloc(key.targets().size(),stack);
            LongBuffer handles=stack.mallocLong(key.targets().size());
            for(int index=0;index<key.targets().size();index++){
                var target=key.targets().get(index);
                var view=target.image().view(target.mip(),1,target.layer(),key.layers(),VK_IMAGE_VIEW_TYPE_2D,target.aspects());
                views.add(view);pool.privateViewCreated();handles.put(view.handle());
                int layout=target.depth()?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                attachments.get(index).format(target.format()).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(target.clear()?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD).stencilStoreOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .initialLayout(layout).finalLayout(layout);
            }
            handles.flip();
            VkAttachmentReference.Buffer colors=VkAttachmentReference.calloc(key.colors().size(),stack);
            for(int slot=0;slot<key.colors().size();slot++)colors.get(slot).attachment(key.colors().get(slot)).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(colors.remaining()).pColorAttachments(colors);
            if(key.depth()!=VK_ATTACHMENT_UNUSED)subpass.pDepthStencilAttachment(VkAttachmentReference.calloc(stack).attachment(key.depth()).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
            VkSubpassDependency.Buffer dependencies=VkSubpassDependency.calloc(2,stack);
            int stages=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            int access=VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            dependencies.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).dstStageMask(stages).srcAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(access);
            dependencies.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(stages).dstStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).srcAccessMask(access).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
            LongBuffer pointer=stack.mallocLong(1);
            check(vkCreateRenderPass(owner.context().device(),VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachments).pSubpasses(subpass).pDependencies(dependencies),null,pointer),"overhaul render pass");renderPass=pointer.get(0);pool.renderPassCreated();
            check(vkCreateFramebuffer(owner.context().device(),VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass).pAttachments(handles).width(key.width()).height(key.height()).layers(key.layers()),null,pointer),"overhaul framebuffer");framebuffer=pointer.get(0);pool.framebufferCreated();
        }catch(RuntimeException|Error failure){destroyNative();throw failure;}
    }
    void retainUntil(FrameRing.Submission completion){for(var view:views)view.retainUntil(completion);}
    private void destroyNative(){
        if(framebuffer!=0)vkDestroyFramebuffer(owner.context().device(),framebuffer,null);
        if(renderPass!=0)vkDestroyRenderPass(owner.context().device(),renderPass,null);
        framebuffer=renderPass=0;
        for(var view:views)view.close();views.clear();
    }
    @Override public void close(){
        if(closed)return;closed=true;
        try{destroyNative();}finally{pins.close();}
    }
}
