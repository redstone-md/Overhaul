package dev.overhaul.vulkan;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class NativeViewLifetimeTest {
    @Test void privateAttachmentViewCloseSkipsLaterImageOnlyUseWhileParentCloseWaits() throws Exception {
        int usage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try(VulkanContext context=VulkanContext.openHeadless(VulkanContext.validationAvailable());GpuAllocator allocator=new GpuAllocator(context);
            GpuImage image=allocator.createImage(ImageFormat.RGBA8_UNORM,4,4,1,1,usage);
            GpuBuffer readback=allocator.hostBuffer(64,VK_BUFFER_USAGE_TRANSFER_DST_BIT)){
            VkDevice device=context.device();long event=createEvent(device);
            ScheduledExecutorService signaler=Executors.newSingleThreadScheduledExecutor();
            try{
                try(FrameRing ring=new FrameRing(context,2)){
                    try{
                        GpuImageView attachment=image.view(0,1,0,1);
                        List<GpuImage.LayoutChange> transitions=new ArrayList<>();
                        try(Framebuffer framebuffer=new Framebuffer(context,attachment)){
                            FrameRing.Submission pass=ring.submit(frame->{
                                transitions.add(image.recordTransition(frame.command(),0,1,0,1,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
                                framebuffer.record(frame.command());
                            });
                            for(GpuImage.LayoutChange transition:transitions)transition.commit();
                            for(GpuImage.LayoutChange transition:transitions)transition.close();transitions.clear();
                            attachment.retainUntil(pass);image.retainUntil(pass);pass.await();
                        }
                        FrameRing.Submission later=ring.submit(frame->{
                            try(MemoryStack stack=MemoryStack.stackPush()){
                                vkCmdWaitEvents(frame.command(),stack.longs(event),VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,null,null,null);
                                transitions.add(image.recordTransition(frame.command(),0,1,0,1,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL));
                                VkImageSubresourceRange.Buffer range=VkImageSubresourceRange.calloc(1,stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                                VkClearColorValue clear=VkClearColorValue.calloc(stack).float32(2,1).float32(3,1);
                                vkCmdClearColorImage(frame.command(),image.handle(),VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,clear,range);
                                transitions.add(image.recordTransition(frame.command(),0,1,0,1,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL));
                                VkBufferImageCopy.Buffer copy=VkBufferImageCopy.calloc(1,stack);
                                copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);copy.imageExtent().set(4,4,1);
                                vkCmdCopyImageToBuffer(frame.command(),image.handle(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback.handle(),copy);
                                VkMemoryBarrier.Buffer host=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                                vkCmdPipelineBarrier(frame.command(),VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,host,null,null);
                            }
                        });
                        for(GpuImage.LayoutChange transition:transitions)transition.commit();
                        for(GpuImage.LayoutChange transition:transitions)transition.close();
                        image.retainUntil(later);assertFalse(later.await(0));
                        long waits=ring.blockingWaitCalls();
                        // A fallback release prevents a regression from hanging: exact native call counters,
                        // rather than elapsed timing, determine whether closing the private view waited.
                        ScheduledFuture<Integer> release=signaler.schedule(()->vkSetEvent(device,event),100,TimeUnit.MILLISECONDS);
                        attachment.close();
                        assertEquals(waits,ring.blockingWaitCalls(),"A private view must wait only for its own completed use");
                        assertThrows(IllegalStateException.class,attachment::handle);
                        image.close();
                        assertEquals(waits+1,ring.blockingWaitCalls(),"Parent image must still observe its pending image-only use");
                        assertEquals(VK_SUCCESS,release.get(5,TimeUnit.SECONDS));assertTrue(later.await(0));
                        ByteBuffer pixels=readback.read();
                        for(int offset=0;offset<64;offset+=4){assertEquals(0,pixels.get(offset));assertEquals(255,pixels.get(offset+2)&255);assertEquals(255,pixels.get(offset+3)&255);}
                    }finally{
                        check(vkSetEvent(device,event),"release lifetime test event");signaler.shutdownNow();
                        assertTrue(signaler.awaitTermination(5,TimeUnit.SECONDS));
                    }
                }
                context.assertValidationClean();
            }finally{vkDestroyEvent(device,event,null);}
        }
    }

    @Test void preciseViewTrackingRejectsForeignSubmissionWithoutClosingSiblingResources(){
        try(VulkanContext context=VulkanContext.openHeadless();GpuAllocator allocator=new GpuAllocator(context);
            GpuImage image=allocator.createImage(ImageFormat.RGBA8_UNORM,2,2,1,1,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
            GpuImageView view=image.view(0,1,0,1);GpuImageView sibling=image.view(0,1,0,1);
            FrameRing own=new FrameRing(context,1);VulkanContext foreignContext=VulkanContext.openHeadless();FrameRing foreign=new FrameRing(foreignContext,1)){
            FrameRing.Submission accepted=own.submit(frame->{});view.retainUntil(accepted);
            FrameRing.Submission rejected=foreign.submit(frame->{});
            assertThrows(IllegalArgumentException.class,()->view.retainUntil(rejected));
            view.close();assertTrue(sibling.handle()!=0);assertTrue(image.handle()!=0);rejected.await();
        }
    }

    private static long createEvent(VkDevice device){
        try(MemoryStack stack=MemoryStack.stackPush()){
            LongBuffer result=stack.mallocLong(1);check(vkCreateEvent(device,VkEventCreateInfo.calloc(stack).sType$Default(),null,result),"lifetime test event");return result.get(0);
        }
    }

    private static final class Framebuffer implements AutoCloseable {
        private final VulkanContext context;private long pass,framebuffer;
        private Framebuffer(VulkanContext context,GpuImageView view){
            this.context=context;
            try(MemoryStack stack=MemoryStack.stackPush()){
                VkAttachmentDescription.Buffer attachment=VkAttachmentDescription.calloc(1,stack).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                VkAttachmentReference.Buffer color=VkAttachmentReference.calloc(1,stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                VkSubpassDescription.Buffer subpass=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(color);
                LongBuffer handle=stack.mallocLong(1);
                check(vkCreateRenderPass(context.device(),VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachment).pSubpasses(subpass),null,handle),"lifetime test render pass");pass=handle.get(0);
                check(vkCreateFramebuffer(context.device(),VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(pass)
                    .pAttachments(stack.longs(view.handle())).width(4).height(4).layers(1),null,handle),"lifetime test framebuffer");framebuffer=handle.get(0);
            }catch(RuntimeException|Error failure){close();throw failure;}
        }
        private void record(VkCommandBuffer command){
            try(MemoryStack stack=MemoryStack.stackPush()){
                VkClearValue.Buffer clear=VkClearValue.calloc(1,stack);clear.color().float32(0,.25f).float32(1,.5f).float32(2,.75f).float32(3,1);
                VkRenderPassBeginInfo begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(pass).framebuffer(framebuffer).pClearValues(clear);begin.renderArea().extent().set(4,4);
                vkCmdBeginRenderPass(command,begin,VK_SUBPASS_CONTENTS_INLINE);vkCmdEndRenderPass(command);
            }
        }
        @Override public void close(){if(framebuffer!=0)vkDestroyFramebuffer(context.device(),framebuffer,null);if(pass!=0)vkDestroyRenderPass(context.device(),pass,null);framebuffer=pass=0;}
    }
}
