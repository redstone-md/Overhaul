package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import dev.overhaul.shader.compile.*;
import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.ImageRegion;
import dev.overhaul.vulkan.VulkanContext;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

@Tag("backend-native")
class DescriptorReuseNativeTest {
    private static final int SIZE=16;
    private static final String VERTEX="""
            #version 450
            void main(){vec2 p[3]=vec2[](vec2(-1,-1),vec2(3,-1),vec2(-1,3));gl_Position=vec4(p[gl_VertexIndex],.5,1);}
            """;
    private static final String FRAGMENT="""
            #version 450
            layout(set=0,binding=0,std140)uniform Params{vec4 color;}params;
            layout(location=0)out vec4 result;void main(){result=params.color;}
            """;

    @Test void acceptedPassCloseWaitsForFenceThenPoolReusesAcrossDestroyedPipelinesWithFreshBindings()throws Exception{
        var owner=device();var cache=owner.descriptorPools();
        try(AutoCloseable cleanup=owner::close;var compiler=new ShadercCompiler();
            var target=target(owner);var view=owner.createTextureView(target,0,1);
            var ring=new FrameRing(owner.context(),1);var gate=new EventGate(owner.context())){
            var pipeline=pipeline(owner,compiler);var red=owner.createBuffer(()->"red",GpuBuffer.USAGE_UNIFORM,color(1,0,0));
            var encoder=owner.createCommandEncoder();try(var uploaded=encoder.createFence()){assertTrue(uploaded.awaitCompletion(-1));}
            var pass=pass(owner,view,pipeline,red);
            var submission=ring.submit(frame->{gate.waitForSignal(frame.command());pass.record(frame.command());frame.retireAfterFence(pass);});
            try{
                pass.complete();pass.retainAttachmentsUntil(submission);pipeline.close();red.close();
                assertEquals(1,cache.stats().activePools());assertEquals(0,cache.stats().resetPools());
                assertThrows(IllegalStateException.class,cache::close,"An active descriptor pool cannot be destroyed");
                assertDoesNotThrow(pipeline::descriptorLayout);assertDoesNotThrow(()->owner.buffer(red).handle());
                // The host releases the native event independently; close must wait before releasing sets and pins.
                Thread release=gate.signalLater();
                try{pass.close();}finally{gate.signal();release.join();gate.checkFailure();}
            }finally{gate.signal();submission.await();pass.close();}
            assertTrue(submission.await(0));assertEquals(0,cache.stats().activePools());assertEquals(1,cache.stats().idlePools());
            assertThrows(IllegalStateException.class,pipeline::descriptorLayout);assertThrows(IllegalStateException.class,()->owner.buffer(red));
            pixel(owner,target,255,0,0);

            var next=pipeline(owner,compiler);var green=owner.createBuffer(()->"green",GpuBuffer.USAGE_UNIFORM,color(0,1,0));
            var second=encoder.createRenderPass(descriptor(view));second.setPipeline(next);second.setUniform(0,green.slice());second.draw(3,1,0,0);encoder.submitRenderPass();
            next.close();green.close();try(var fence=encoder.createFence()){assertTrue(fence.awaitCompletion(-1));}
            pixel(owner,target,0,255,0);var stats=cache.stats();
            assertEquals(1,stats.createdPools());assertEquals(1,stats.reusedPools());assertEquals(2,stats.resetPools());
            assertEquals(0,stats.activePools());assertEquals(1,stats.idlePools());assertThrows(IllegalStateException.class,next::descriptorLayout);
        }
        assertEquals(0,cache.stats().idlePools());assertEquals(cache.stats().createdPools(),cache.stats().destroyedPools(),"Stats remain readable after device shutdown");
    }

    @Test void inFlightPoolIsExclusiveAndIdleCountRemainsBounded()throws Exception{
        try(var context=VulkanContext.openHeadless();var ring=new FrameRing(context,1);
            var cache=new DescriptorPoolCache(context,1,0);var gate=new EventGate(context)){
            var shape=new DescriptorPoolCache.Shape(0,0,0,1);long layout=emptyLayout(context);
            try{
                var first=cache.acquire(shape);long firstHandle=first.handle();allocate(context,first,layout);
                var submission=ring.submit(frame->{gate.waitForSignal(frame.command());frame.retireAfterFence(first);});first.submitted(submission);
                try{
                    assertThrows(IllegalStateException.class,first::close);assertEquals(0,cache.stats().resetPools());
                    var second=cache.acquire(shape);long secondHandle=second.handle();assertNotEquals(firstHandle,secondHandle);
                    allocate(context,second,layout);second.close();
                    var reused=cache.acquire(shape);assertEquals(secondHandle,reused.handle());allocate(context,reused,layout);reused.close();
                    assertEquals(1,cache.stats().activePools());assertEquals(1,cache.stats().idlePools());assertEquals(1,cache.stats().reusedPools());
                }finally{gate.signal();submission.await();}
                assertEquals(0,cache.stats().activePools());assertEquals(1,cache.stats().idlePools());assertEquals(1,cache.stats().evictedPools());
                var retired=cache.acquire(shape);assertEquals(firstHandle,retired.handle());allocate(context,retired,layout);retired.close();
            }finally{vkDestroyDescriptorSetLayout(context.device(),layout,null);}
        }
    }

    @Test void failedUnsubmittedDescriptorUpdateRollsBackAndCapacityBudgetEvicts()throws Exception{
        var owner=device();
        try(AutoCloseable cleanup=owner::close;var compiler=new ShadercCompiler();var target=target(owner);var view=owner.createTextureView(target,0,1);
            var pipeline=pipeline(owner,compiler);var blue=owner.createBuffer(()->"blue",GpuBuffer.USAGE_UNIFORM,color(0,0,1))){
            try(var abandoned=new PassDescriptors(owner)){
                assertThrows(IllegalArgumentException.class,()->abandoned.set(pipeline,new Object[]{"invalid buffer binding"}));
                assertEquals(1,owner.descriptorPools().stats().activePools());
            }
            assertEquals(0,owner.descriptorPools().stats().activePools());assertEquals(1,owner.descriptorPools().stats().resetPools());
            var encoder=owner.createCommandEncoder();var pass=encoder.createRenderPass(descriptor(view));
            pass.setPipeline(pipeline);pass.setUniform(0,blue.slice());pass.draw(3,1,0,0);encoder.submitRenderPass();
            try(var fence=encoder.createFence()){assertTrue(fence.awaitCompletion(-1));}
            pixel(owner,target,0,0,255);assertEquals(1,owner.descriptorPools().stats().createdPools());assertEquals(1,owner.descriptorPools().stats().reusedPools());
            try(var bounded=new DescriptorPoolCache(owner.context(),4,1)){
                bounded.acquire(new DescriptorPoolCache.Shape(1,0,0,1)).close();
                bounded.acquire(new DescriptorPoolCache.Shape(0,1,0,1)).close();
                assertEquals(1,bounded.stats().idleDescriptors());assertEquals(1,bounded.stats().idlePools());assertEquals(1,bounded.stats().evictedPools());
                bounded.acquire(new DescriptorPoolCache.Shape(2,0,0,1)).close();
                assertEquals(1,bounded.stats().idleDescriptors());assertEquals(2,bounded.stats().destroyedPools(),"Oversized returned pools must not enter the idle cache");
            }
        }
    }

    private static OverhaulDeviceBackend device(){return new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));}
    private static GpuTexture target(OverhaulDeviceBackend owner){return owner.createTexture("descriptor reuse",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);}
    private static RenderPassDescriptor descriptor(GpuTextureView view){return RenderPassDescriptor.builder(()->"descriptor reuse").withColorAttachment(view,Optional.of(new Vector4f(0,0,0,1))).build();}
    private static OverhaulRenderPass pass(OverhaulDeviceBackend owner,GpuTextureView view,OverhaulPipeline pipeline,GpuBuffer uniform){
        var pass=new OverhaulRenderPass(owner,descriptor(view));pass.setPipeline(pipeline);pass.setUniform(0,uniform.slice());pass.draw(3,1,0,0);pass.seal();return pass;
    }
    private static OverhaulPipeline pipeline(OverhaulDeviceBackend owner,ShadercCompiler compiler){
        var shaders=List.of(new BackendRenderPipeline.CreateInfo.Shader("reuse.vert","main",new Module(compiler.compile("reuse.vert",VERTEX,ShaderStage.VERTEX),ShaderType.VERTEX)),
                new BackendRenderPipeline.CreateInfo.Shader("reuse.frag","main",new Module(compiler.compile("reuse.frag",FRAGMENT,ShaderStage.FRAGMENT),ShaderType.FRAGMENT)));
        return(OverhaulPipeline)OverhaulPipeline.compile(owner,new BackendRenderPipeline.CreateInfo("reuse",shaders,List.of(),List.of(),
                List.of(new BindGroupLayout.UniformDescription("Params",UniformType.UNIFORM_BUFFER)),0,null,PolygonMode.FILL,false,List.of(ColorTargetState.DEFAULT),PrimitiveTopology.TRIANGLES)).finishCompile();
    }
    private record Module(SpirvBinary binary,ShaderType type)implements SpvModule{
        @Override public void close(){}@Override public ByteBuffer spv(){return binary.directBuffer();}
        @Override public Reflection reflect(){throw new UnsupportedOperationException("Explicit test bindings");}@Override public Reflection getReflectionInfoIfAvailable(){return null;}
    }
    private static ByteBuffer color(float red,float green,float blue){return ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putFloat(red).putFloat(green).putFloat(blue).putFloat(1).flip();}
    private static void pixel(OverhaulDeviceBackend owner,GpuTexture target,int red,int green,int blue){
        ByteBuffer pixels=owner.commands().readback(owner.image(target),ImageRegion.whole(owner.image(target),0,0),0);int offset=(SIZE/2*SIZE+SIZE/2)*4;
        int[] expected={red,green,blue,255};for(int channel=0;channel<4;channel++)assertEquals(expected[channel],pixels.get(offset+channel)&255,1,"channel "+channel);
    }
    private static long emptyLayout(VulkanContext context){try(MemoryStack stack=MemoryStack.stackPush()){
        var pointer=stack.mallocLong(1);check(vkCreateDescriptorSetLayout(context.device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default(),null,pointer),"test empty layout");return pointer.get(0);
    }}
    private static void allocate(VulkanContext context,DescriptorPoolCache.Lease lease,long layout){try(MemoryStack stack=MemoryStack.stackPush()){
        check(vkAllocateDescriptorSets(context.device(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(lease.handle()).pSetLayouts(stack.longs(layout)),stack.mallocLong(1)),"test descriptor allocation");lease.allocated();
    }}
    /** An unsignaled native event keeps the real GPU fence pending until a host release. */
    private static final class EventGate implements AutoCloseable{
        private final VkDevice device;private final long event;private final AtomicReference<Throwable> failure=new AtomicReference<>();
        EventGate(VulkanContext context){device=context.device();try(MemoryStack stack=MemoryStack.stackPush()){
            var pointer=stack.mallocLong(1);check(vkCreateEvent(device,VkEventCreateInfo.calloc(stack).sType$Default(),null,pointer),"test event");event=pointer.get(0);
        }}
        void waitForSignal(VkCommandBuffer command){try(MemoryStack stack=MemoryStack.stackPush()){
            vkCmdWaitEvents(command,stack.longs(event),VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,null,null,null);
        }}
        synchronized void signal(){check(vkSetEvent(device,event),"test host event signal");}
        Thread signalLater(){Thread thread=new Thread(()->{try{LockSupport.parkNanos(100_000_000);signal();}catch(Throwable error){failure.set(error);}},"descriptor-test-release");thread.start();return thread;}
        void checkFailure(){if(failure.get()!=null)throw new AssertionError("Native event release failed",failure.get());}
        @Override public void close(){vkDestroyEvent(device,event,null);}
    }
}
