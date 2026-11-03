package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.VulkanContext;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

@Tag("backend-native")
class AttachmentReuseNativeTest {
    @Test void explicitLeaseCloseThatRetiresItsOwnCallbackReturnsTheBundleExactlyOnce() {
        var owner=device();
        try(var texture=texture(owner,2,1);var view=owner.createTextureView(texture,0,1)){
            var image=owner.image(texture);
            var target=new AttachmentKey.Target(image,0,0,image.format().vkFormat(),image.format().aspectMask(),false,true);
            var key=new AttachmentKey(List.of(target),List.of(0),VK_ATTACHMENT_UNUSED,2,2,1);
            var lease=owner.attachments().acquire(key,List.of((OverhaulTextureView)view));
            owner.queue().enqueue(new OverhaulGpuOperation(){
                @Override public void record(VkCommandBuffer command){lease.create();}
                @Override public void submitted(dev.overhaul.vulkan.FrameRing.Submission completion){lease.retainUntil(completion);}
                @Override public void close(){lease.close();}
            });
            owner.queue().flush();
            // Waiting here invokes the batch retirement callback, which re-enters this same lease close.
            lease.close();lease.close();
            assertEquals(0,stat(owner,"Active"));assertEquals(1,stat(owner,"Idle"));
            try(var reused=owner.attachments().acquire(key,List.of((OverhaulTextureView)view))){
                reused.create();assertEquals(0,stat(owner,"Idle"));
            }
            assertEquals(1,stat(owner,"Idle"));assertEquals(1,stat(owner,"FramebufferCreations"));
        }finally{owner.close();}
    }

    @Test void retiredBundlesReuseNativeObjectsButPreservePerPassClearAndLoadSemantics() {
        var owner=device();
        try(var texture=texture(owner,8,1);var view=owner.createTextureView(texture,0,1)){
            var encoder=owner.createCommandEncoder();
            submit(encoder,color(view,1,0,0));await(owner);
            assertEquals(1,stat(owner,"RenderPassCreations"));assertEquals(1,stat(owner,"PrivateViewCreations"));
            var region=RenderPassDescriptor.builder(()->"new clear value and area")
                    .withColorAttachment(view,Optional.of(new Vector4f(0,1,0,1)))
                    .withRenderArea(new RenderPass.RenderArea(2,2,4,4)).build();
            submit(encoder,region);await(owner);
            assertEquals(1,stat(owner,"FramebufferCreations"));assertEquals(1,stat(owner,"Reuses"));
            ByteBuffer pixels=read(owner,texture,0);
            pixel(pixels,8,0,0,255,0,0,255);pixel(pixels,8,3,3,0,255,0,255);
            submit(encoder,RenderPassDescriptor.builder(()->"load").withColorAttachment(view,Optional.empty()).build());await(owner);
            assertEquals(2,stat(owner,"RenderPassCreations"),"LOAD cannot reuse a CLEAR render pass");
            pixels=read(owner,texture,0);pixel(pixels,8,0,0,255,0,0,255);pixel(pixels,8,3,3,0,255,0,255);
            submit(encoder,color(view,0,0,1));await(owner);
            assertEquals(2,stat(owner,"FramebufferCreations"));assertEquals(2,stat(owner,"Reuses"));
            pixel(read(owner,texture,0),8,0,0,0,0,255,255);
        }finally{owner.close();}
    }

    @Test void orderedSlotsHolesDepthAndMipExtentsSelectDistinctCompatibleBundles() {
        var owner=device();
        try(var first=texture(owner,8,2);var second=owner.createTexture("float MRT",usage(),GpuFormat.RGBA16_FLOAT,8,8,1,1);
            var depth=owner.createTexture("depth",usage(),GpuFormat.D32_FLOAT,8,8,1,1);
            var a=owner.createTextureView(first,0,1);var b=owner.createTextureView(second,0,1);
            var d=owner.createTextureView(depth,0,1);var mip=owner.createTextureView(first,1,1)){
            var encoder=owner.createCommandEncoder();
            submit(encoder,RenderPassDescriptor.builder(()->"MRT holes")
                    .withColorAttachment(a,Optional.of(new Vector4f(1,0,0,1))).withUnusedColorAttachment()
                    .withColorAttachment(b,Optional.of(new Vector4f(0,0,1,1))).withDepthAttachment(d,OptionalDouble.of(.25)).build());await(owner);
            pixel(read(owner,first,0),8,2,2,255,0,0,255);
            assertEquals(0x3c00,read(owner,second,0).order(ByteOrder.nativeOrder()).getShort(4)&65535,"Half-float blue clear");
            assertEquals(.25f,read(owner,depth,0).order(ByteOrder.nativeOrder()).getFloat(0),.0001f);
            submit(encoder,RenderPassDescriptor.builder(()->"reordered")
                    .withColorAttachment(b,Optional.of(new Vector4f(0,1,0,1))).withUnusedColorAttachment()
                    .withColorAttachment(a,Optional.of(new Vector4f(1,1,0,1))).withDepthAttachment(d,OptionalDouble.of(.75)).build());await(owner);
            assertEquals(2,stat(owner,"FramebufferCreations"));pixel(read(owner,first,0),8,2,2,255,255,0,255);
            assertEquals(0x3c00,read(owner,second,0).order(ByteOrder.nativeOrder()).getShort(2)&65535,"Reordered half-float green clear");
            submit(encoder,RenderPassDescriptor.builder(()->"moved hole").withUnusedColorAttachment()
                    .withColorAttachment(b,Optional.of(new Vector4f(0,1,1,1)))
                    .withColorAttachment(a,Optional.of(new Vector4f(1,0,1,1))).withDepthAttachment(d,OptionalDouble.of(.5)).build());await(owner);
            assertEquals(3,stat(owner,"FramebufferCreations"));
            submit(encoder,color(mip,0,1,0));await(owner);
            assertEquals(4,stat(owner,"FramebufferCreations"));pixel(read(owner,first,1),4,1,1,0,255,0,255);
            pixel(read(owner,first,0),8,2,2,255,0,255,255);
        }finally{owner.close();}
    }

    @Test void inFlightLeasesAreExclusiveAndClosingTheOriginallyPinnedViewInvalidatesEquivalentReuse() {
        var owner=device();long event=0;
        try(var texture=texture(owner,4,1);var original=owner.createTextureView(texture,0,1);
            var equivalent=owner.createTextureView(texture,0,1)){
            try(MemoryStack stack=MemoryStack.stackPush()){
                var pointer=stack.mallocLong(1);
                check(vkCreateEvent(owner.context().device(),VkEventCreateInfo.calloc(stack).sType$Default(),null,pointer),"attachment test event");event=pointer.get(0);
            }
            long held=event;
            owner.queue().enqueue(new OverhaulGpuOperation(){
                @Override public void record(VkCommandBuffer command){
                    try(MemoryStack stack=MemoryStack.stackPush()){
                        vkCmdWaitEvents(command,stack.longs(held),VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,null,null,null);
                    }
                }
                @Override public void close(){}
            });
            var encoder=owner.createCommandEncoder();submit(encoder,color(original,1,0,0));
            var first=owner.queue().flush();assertFalse(first.await(0));
            submit(encoder,color(original,0,1,0));var second=owner.queue().flush();
            assertFalse(second.await(0));assertEquals(2,stat(owner,"Active"));assertEquals(0,stat(owner,"Idle"));
            assertEquals(2,stat(owner,"FramebufferCreations"));assertEquals(0,stat(owner,"Reuses"));
            assertThrows(IllegalStateException.class,()->owner.attachments().close(),"Pool close cannot destroy active framebuffers");
            check(vkSetEvent(owner.context().device(),event),"release attachment test event");owner.queue().await(second,-1L);
            assertEquals(0,stat(owner,"Active"));assertEquals(2,stat(owner,"Idle"));
            submit(encoder,color(equivalent,0,0,1));
            assertEquals(1,stat(owner,"Reuses"));
            original.close();
            assertEquals(0,stat(owner,"Idle"),"Original view close must evict the other idle bundle immediately");
            await(owner);
            assertEquals(0,stat(owner,"Active"));assertEquals(0,stat(owner,"Idle"),"The active equivalent-view lease must be discarded on retirement");
            pixel(read(owner,texture,0),4,1,1,0,0,255,255);
            submit(encoder,color(equivalent,1,1,0));await(owner);
            assertEquals(3,stat(owner,"FramebufferCreations"));assertEquals(1,stat(owner,"Idle"));
            texture.close();assertEquals(0,stat(owner,"Idle"),"Parent logical close must also release cache pins");
        }finally{
            if(event!=0){vkSetEvent(owner.context().device(),event);owner.queue().drain();vkDestroyEvent(owner.context().device(),event,null);}
            owner.close();
        }
    }

    @Test void rollbackReturnsUnsubmittedBundlesAndCacheEvictsBeyondItsCapacity() {
        var owner=device();var textures=new ArrayList<GpuTexture>();var views=new ArrayList<GpuTextureView>();
        try{
            var encoder=owner.createCommandEncoder();
            for(int i=0;i<66;i++){
                var texture=texture(owner,2,1);textures.add(texture);var view=owner.createTextureView(texture,0,1);views.add(view);
                submit(encoder,color(view,1,0,0));
                if(i==0){
                    owner.queue().enqueue(new OverhaulGpuOperation(){
                        @Override public void record(VkCommandBuffer command){throw new IllegalStateException("rollback after attachment recording");}
                        @Override public void close(){}
                    });
                    assertThrows(IllegalStateException.class,()->owner.queue().flush());
                    assertEquals(0,stat(owner,"Active"));assertEquals(1,stat(owner,"Idle"));
                    assertEquals(VK_IMAGE_LAYOUT_UNDEFINED,owner.image(texture).layout(0,0));
                    submit(encoder,color(view,0,1,0));
                }
                await(owner);
                assertTrue(stat(owner,"Idle")<=64);assertEquals(0,stat(owner,"Active"));
            }
            assertEquals(66,stat(owner,"FramebufferCreations"));assertEquals(1,stat(owner,"Reuses"));
            assertEquals(64,stat(owner,"Idle"));assertEquals(2,stat(owner,"Evictions"));
            pixel(read(owner,textures.getFirst(),0),2,0,0,0,255,0,255);
            for(var view:views)view.close();assertEquals(0,stat(owner,"Idle"));
            for(var texture:textures)texture.close();
            owner.attachments().close();assertEquals(0,stat(owner,"Active"));
            assertEquals(66,stat(owner,"FramebufferCreations"),"Allocation counters survive pool close");
        }finally{for(var view:views)view.close();for(var texture:textures)texture.close();owner.close();}
    }

    private static OverhaulDeviceBackend device(){return new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));}
    private static int usage(){return GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST;}
    private static GpuTexture texture(OverhaulDeviceBackend owner,int size,int mips){return owner.createTexture("attachment",usage(),GpuFormat.RGBA8_UNORM,size,size,1,mips);}
    private static RenderPassDescriptor color(GpuTextureView view,float r,float g,float b){return RenderPassDescriptor.builder(()->"clear attachment").withColorAttachment(view,Optional.of(new Vector4f(r,g,b,1))).build();}
    private static void submit(OverhaulCommandEncoder encoder,RenderPassDescriptor descriptor){encoder.createRenderPass(descriptor);encoder.submitRenderPass();}
    private static void await(OverhaulDeviceBackend owner){try(var fence=owner.createCommandEncoder().createFence()){assertTrue(fence.awaitCompletion(-1L));}}
    private static long stat(OverhaulDeviceBackend owner,String suffix){return owner.attachments().statistics().get("attachment"+suffix);}
    private static ByteBuffer read(OverhaulDeviceBackend owner,GpuTexture texture,int mip){var image=owner.image(texture);return owner.commands().readback(image,ImageRegion.whole(image,mip,0),0);}
    private static void pixel(ByteBuffer pixels,int width,int x,int y,int...expected){for(int channel=0;channel<4;channel++)assertEquals(expected[channel],pixels.get((y*width+x)*4+channel)&255,1,"channel "+channel);}
}
