package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.overhaul.vulkan.VulkanContext;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class AsyncQueueNativeTest {
    @Test void anOlderFenceStillRetiresItsResourcesAfterANewerReceiptCompletes() {
        var owner=device();
        try {
            var retired=new ArrayList<Integer>(); var encoder=owner.createCommandEncoder();
            owner.queue().enqueue(retirement(retired,1));
            try (var first=encoder.createFence()) {
                owner.queue().enqueue(retirement(retired,2));
                owner.queue().flush().await();
                assertEquals(List.of(2),retired);
                assertTrue(first.awaitCompletion(0));
                assertEquals(List.of(2,1),retired);
                assertEquals(0,owner.queue().inFlightBatches());
            }
        } finally { owner.close(); }
    }
    @Test void gameFencePollsActualPendingGpuWorkAndRetiresExactlyOnce() {
        var owner=device(); long event=0;
        try {
            try (MemoryStack stack=MemoryStack.stackPush()) {
                var pointer=stack.mallocLong(1);
                check(vkCreateEvent(owner.context().device(),VkEventCreateInfo.calloc(stack).sType$Default(),null,pointer),"queue test event");
                event=pointer.get(0);
            }
            long blockedEvent=event;
            var retired=new AtomicInteger();
            owner.queue().enqueue(new OverhaulGpuOperation() {
                @Override public void record(VkCommandBuffer command) {
                    try (MemoryStack stack=MemoryStack.stackPush()) {
                        vkCmdWaitEvents(command,stack.longs(blockedEvent),VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,null,null,null);
                    }
                }
                @Override public void close() { retired.incrementAndGet(); }
            });
            try (var fence=owner.createCommandEncoder().createFence()) {
                try {
                    assertEquals(1,owner.queue().submissions());
                    assertFalse(fence.awaitCompletion(0));
                    assertEquals(0,retired.get());
                    assertEquals(0,owner.queue().blockingWaitCalls());
                    assertThrows(IllegalArgumentException.class,()->fence.awaitCompletion(-2));
                } finally { check(vkSetEvent(owner.context().device(),event),"release queue test event"); }
                assertTrue(fence.awaitCompletion(-1));
                assertEquals(1,retired.get());
                assertTrue(fence.awaitCompletion(0));
                assertEquals(1,retired.get());
            }
        } finally {
            if (event!=0) { vkSetEvent(owner.context().device(),event); owner.queue().drain(); vkDestroyEvent(owner.context().device(),event,null); }
            owner.close();
        }
    }

    @Test void disjointHostMappingKeepsTheBatchWhileAnOverlappingReadWaitsForItsCopy() {
        var owner=device();
        try {
            int far=Math.toIntExact(owner.queue().mappingAlignment()*2);
            int usage=GpuBuffer.USAGE_COPY_SRC|GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_MAP_WRITE;
            try (var source=owner.createBuffer(()->"copy source",usage,filled(32,0x5a));
                 var target=owner.createBuffer(()->"copy target",usage,filled(far+32,0))) {
                var encoder=owner.createCommandEncoder();
                encoder.copyToBuffer(source.slice(0,16),target.slice(0,16));
                source.close();
                assertEquals(1,owner.queue().pendingOperations());
                try (var map=target.map(far,16,false,true)) { map.data().put(0,(byte)37); }
                assertEquals(0,owner.queue().submissions(),"Disjoint map must not split the pending batch");
                try (var map=target.map(0,16,true,false)) {
                    for (int i=0;i<16;i++) assertEquals(0x5a,map.data().get(i)&255);
                }
                assertEquals(1,owner.queue().submissions());
                assertEquals(0,owner.queue().pendingOperations());
            }
        } finally { owner.close(); }
    }

    @Test void readbackCallbacksFollowGpuOrderAndCanQueueFurtherWork() {
        var owner=device();
        try {
            int usage=GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_RENDER_ATTACHMENT;
            try (var texture=owner.createTexture("callback source",usage,GpuFormat.RGBA8_UNORM,2,2,1,1);
                 var first=owner.createBuffer(()->"first pixels",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,16);
                 var second=owner.createBuffer(()->"second pixels",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,16);
                 var followup=owner.createBuffer(()->"callback upload",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,8)) {
                var encoder=owner.createCommandEncoder(); List<Integer> callbacks=new ArrayList<>();
                encoder.clearColorTexture(texture,new Vector4f(1,0,0,1));
                encoder.copyTextureToBuffer(texture,first,0,()-> {
                    try (var pixels=first.map(0,16,true,false)) { assertEquals(255,pixels.data().get(0)&255); }
                    callbacks.add(1); encoder.writeToBuffer(followup.slice(),filled(8,73));
                },0);
                encoder.clearColorTexture(texture,new Vector4f(0,1,0,1));
                encoder.copyTextureToBuffer(texture,second,0,()-> {
                    try (var pixels=second.map(0,16,true,false)) { assertEquals(255,pixels.data().get(1)&255); }
                    callbacks.add(2);
                },0);
                assertTrue(callbacks.isEmpty());
                try (var fence=encoder.createFence()) { assertTrue(fence.awaitCompletion(-1)); }
                assertEquals(List.of(1,2),callbacks);
                try (var map=followup.map(0,8,true,false)) {
                    for (int i=0;i<8;i++) assertEquals(73,map.data().get(i)&255);
                }
            }
        } finally { owner.close(); }
    }

    @Test void recordingFailureRollsBackTheWholeBatchWithoutRunningItsCallbacks() {
        var owner=device();
        try {
            int usage=GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_RENDER_ATTACHMENT;
            try (var image=owner.createTexture("rollback",usage,GpuFormat.RGBA8_UNORM,2,2,1,1);
                 var readback=owner.createBuffer(()->"rollback readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,16)) {
                var encoder=owner.createCommandEncoder(); var callbacks=new AtomicInteger(); var retired=new AtomicInteger();
                encoder.clearColorTexture(image,new Vector4f(1,0,0,1));
                encoder.copyTextureToBuffer(image,readback,0,callbacks::incrementAndGet,0);
                owner.queue().enqueue(new OverhaulGpuOperation() {
                    @Override public void record(VkCommandBuffer command) { throw new IllegalStateException("reject this batch"); }
                    @Override public void close() { retired.incrementAndGet(); }
                });
                assertThrows(IllegalStateException.class,owner.queue()::flush);
                assertEquals(VK_IMAGE_LAYOUT_UNDEFINED,owner.image(image).layout(0,0));
                assertEquals(0,callbacks.get()); assertEquals(1,retired.get());
                assertEquals(0,owner.queue().submissions());
                encoder.clearColorTexture(image,new Vector4f(0,1,0,1));
                encoder.copyTextureToBuffer(image,readback,0,callbacks::incrementAndGet,0);
                try (var mapped=readback.map(0,16,true,false)) { assertEquals(255,mapped.data().get(1)&255); }
                assertEquals(1,callbacks.get());
            }
        } finally { owner.close(); }
    }

    private static OverhaulDeviceBackend device() {
        return new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));
    }
    private static OverhaulGpuOperation retirement(List<Integer> output,int id) {
        return new OverhaulGpuOperation() {
            @Override public void record(VkCommandBuffer command) {}
            @Override public void close() { output.add(id); }
        };
    }
    private static ByteBuffer filled(int size,int value) {
        var bytes=ByteBuffer.allocateDirect(size); while (bytes.hasRemaining()) bytes.put((byte)value); return bytes.flip();
    }
}
