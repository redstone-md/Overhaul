package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.overhaul.vulkan.VulkanContext;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class EncoderNativeTest {
    @Test void realBufferCopiesMappedTransientReuseAndGpuTimestamps() {
        var backend=new OverhaulDeviceBackend(VulkanContext.openHeadless(VulkanContext.validationAvailable()),
                new GpuDebugOptions(0,false,false,VulkanContext.validationAvailable()));
        var device=new FrontendGpuDevice(backend);
        try {
            int usage=GpuBuffer.USAGE_COPY_SRC|GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_MAP_WRITE;
            try (var source=device.createBuffer(()->"source",usage,filled(64,127));
                 var target=device.createBuffer(()->"destination",usage,filled(64,90));
                 var queries=device.createTimestampQueryPool(2)) {
                var encoder=device.createCommandEncoder();
                ByteBuffer payload=ByteBuffer.allocateDirect(16);
                for (int index=0;index<16;index++) payload.put((byte)(index+3));
                payload.flip();
                encoder.writeToBuffer(source.slice(8,16),payload);
                assertEquals(0,payload.position());
                encoder.copyToBuffer(source.slice(8,16),target.slice(17,16));
                try (var mapped=target.map(0,64,true,false)) {
                    for (int index=0;index<64;index++) assertEquals(index>=17 && index<33 ? index-14 : 90,mapped.data().get(index)&255);
                }
                var transientMemory=encoder.transientMemory();
                ByteBuffer cpu=transientMemory.allocateCpu(13,64,20,4);
                assertEquals(0,MemoryUtil.memAddress(cpu)&63);
                assertTrue(cpu.remaining()>=20);
                GpuBufferSlice first;
                try (var mapped=transientMemory.allocateGpuMapped(13,256,GpuBuffer.USAGE_VERTEX,16,4)) {
                    first=mapped.slice(); assertEquals(0,first.offset()%256);
                    for (int index=0;index<13;index++) mapped.data().put(index,(byte)(100+index));
                    assertThrows(IllegalStateException.class,encoder::submit,"Live mapping must prevent arena rewind");
                }
                encoder.copyToBuffer(first.slice(0,13),target.slice(0,13));
                try (var mapped=target.map(0,13,true,false)) {
                    for (int index=0;index<13;index++) assertEquals(100+index,mapped.data().get(index)&255);
                }
                encoder.submit();
                try (var reused=transientMemory.allocateGpuMapped(13,256,GpuBuffer.USAGE_VERTEX,16,4)) {
                    assertSame(first.buffer(),reused.slice().buffer());
                    assertEquals(first.offset(),reused.slice().offset());
                }
                try (var empty=transientMemory.allocateStaging(0,16,GpuBuffer.USAGE_COPY_SRC)) {
                    assertEquals(0,empty.data().remaining()); assertEquals(0,empty.slice().length());
                }
                assertTrue(queries.getValue(0).isEmpty());
                encoder.writeTimestamp(queries,0);
                assertTrue(queries.getValue(0).isPresent());
                assertTrue(queries.getValue(1).isEmpty());
                encoder.writeTimestamp(queries,1);
                assertTrue(queries.getValue(1).isPresent());
                backend.getTimestampCalibrationOffset();
                try (var fence=encoder.createFence()) { assertTrue(fence.awaitCompletion(0)); }
                encoder.submit();
            }
            assertTrue(backend.context().validationErrors().isEmpty(),backend.context().validationErrors().toString());
        } finally { device.close(); }
    }

    @Test void stridedImageUploadsMipCopiesAndPartialColorDepthClearsMatchReadback() {
        var backend=new OverhaulDeviceBackend(VulkanContext.openHeadless(VulkanContext.validationAvailable()),
                new GpuDebugOptions(0,false,false,VulkanContext.validationAvailable()));
        var device=new FrontendGpuDevice(backend);
        try {
            int imageUsage=GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT;
            try (var source=device.createTexture("source image",imageUsage,GpuFormat.RGBA8_UNORM,8,8,1,3);
                 var destination=device.createTexture("destination image",imageUsage,GpuFormat.RGBA8_UNORM,8,8,1,3);
                 var depth=device.createTexture("depth",imageUsage,GpuFormat.D32_FLOAT,8,8,1,3);
                 var pixels=device.createBuffer(()->"source pixels",GpuBuffer.USAGE_COPY_SRC,gradient());
                 var readback=device.createBuffer(()->"readback",GpuBuffer.USAGE_MAP_READ|GpuBuffer.USAGE_COPY_DST,256)) {
                var encoder=device.createCommandEncoder();
                encoder.clearColorTexture(source,new Vector4f(0,0,1,1));
                encoder.clearColorTexture(destination,new Vector4f(1,0,0,1));
                encoder.copyBufferToTexture(pixels.slice(),2,1,8,8,source,1,2,3,4,0,0);
                encoder.copyTextureToTexture(source,destination,0,3,1,1,2,3,4);
                AtomicInteger callbacks=new AtomicInteger();
                encoder.copyTextureToBuffer(destination,readback,0,callbacks::incrementAndGet,0);
                assertEquals(1,callbacks.get());
                try (var mapped=readback.map(0,256,true,false)) {
                    for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
                        int offset=(y*8+x)*4;
                        boolean copied=x>=3 && x<6 && y>=1 && y<5;
                        assertEquals(copied ? (x-1)*17 : 255,mapped.data().get(offset)&255);
                        assertEquals(copied ? y*23 : 0,mapped.data().get(offset+1)&255);
                        assertEquals(copied ? 7 : 0,mapped.data().get(offset+2)&255);
                    }
                }
                encoder.copyTextureToBuffer(destination,readback,16,()->{},1);
                try (var mapped=readback.map(16,64,true,false)) {
                    for (int offset=0;offset<64;offset+=4) assertEquals(255,mapped.data().get(offset)&255);
                }
                encoder.clearDepthTexture(depth,1);
                encoder.clearColorAndDepthTextures(destination,new Vector4f(0,1,0,1),depth,.25,2,2,2,3,0);
                encoder.copyTextureToBuffer(depth,readback,0,()->{},0);
                try (var mapped=readback.map(0,256,true,false)) {
                    mapped.data().order(ByteOrder.nativeOrder());
                    for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
                        assertEquals(x>=2 && x<4 && y>=2 && y<5 ? .25f : 1f,mapped.data().getFloat((y*8+x)*4));
                    }
                }
                encoder.copyTextureToBuffer(destination,readback,0,()->{},0);
                try (var mapped=readback.map(0,256,true,false)) {
                    assertEquals(255,mapped.data().get((3*8+2)*4+1)&255);
                    assertEquals(255,mapped.data().get(0)&255,"partial clear must preserve the surrounding red image");
                }
                ByteBuffer patch=filled(16,21);
                encoder.writeToTexture(destination,patch,2,0,0,0,2,2);
                encoder.copyTextureToBuffer(destination,readback,0,()->{},2);
                try (var mapped=readback.map(0,16,true,false)) {
                    for (int index=0;index<16;index++) assertEquals(21,mapped.data().get(index)&255);
                }
                encoder.submit();
            }
            assertTrue(backend.context().validationErrors().isEmpty(),backend.context().validationErrors().toString());
        } finally { device.close(); }
    }

    private static ByteBuffer filled(int bytes,int value) {
        ByteBuffer result=ByteBuffer.allocateDirect(bytes);
        while (result.hasRemaining()) result.put((byte)value);
        return result.flip();
    }
    private static ByteBuffer gradient() {
        ByteBuffer result=ByteBuffer.allocateDirect(256);
        for (int y=0;y<8;y++) for (int x=0;x<8;x++) result.put((byte)(x*17)).put((byte)(y*23)).put((byte)7).put((byte)255);
        return result.flip();
    }
}
