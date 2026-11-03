package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import dev.overhaul.vulkan.VulkanContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

@Tag("backend-native")
class DeviceBufferNativeTest {
    @Test void unmappableInitialDataAndLaterWritesStayOrderedWithoutHostAccessToTheAllocation() {
        var owner=device();
        var storageStatistics=owner.bufferStorage();
        try {
            var encoder=owner.createCommandEncoder();
            ByteBuffer input=ByteBuffer.allocateDirect(80);
            for (int i=0;i<80;i++) input.put(i,(byte)(i+7));
            input.position(8).limit(72);
            int usage=GpuBuffer.USAGE_COPY_SRC|GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_VERTEX;
            try (var source=owner.createBuffer(()->"static geometry",usage,input);
                 var copied=owner.createBuffer(()->"device copy",usage,64);
                 var readback=owner.createBuffer(()->"readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,128)) {
                var nativeBuffer=owner.buffer(source);
                assertThrows(IllegalStateException.class,()->nativeBuffer.mapRange(0,4,true,false));
                assertThrows(IllegalArgumentException.class,()->source.map(0,4,true,false));
                assertEquals(8,input.position()); assertEquals(72,input.limit());
                for (int i=8;i<72;i++) input.put(i,(byte)0);
                encoder.copyToBuffer(source.slice(),readback.slice(0,64));
                ByteBuffer patch=ByteBuffer.allocateDirect(16);
                for (int i=0;i<16;i++) patch.put(i,(byte)(200+i));
                encoder.writeToBuffer(source.slice(16,16),patch);
                for (int i=0;i<16;i++) patch.put(i,(byte)0);
                encoder.copyToBuffer(source.slice(),copied.slice());
                encoder.copyToBuffer(copied.slice(),readback.slice(64,64));
                assertEquals(0,owner.queue().submissions(),"Uploads must share the ordered batch");
                source.close(); copied.close();
                try (var mapped=readback.map(0,128,true,false)) {
                    for (int i=0;i<64;i++) {
                        assertEquals(i+15,mapped.data().get(i)&255,"Initial data snapshot");
                        assertEquals(i>=16&&i<32?184+i:i+15,mapped.data().get(64+i)&255,"Ordered partial update");
                    }
                }
                assertThrows(IllegalStateException.class,()->owner.buffer(source));
                assertEquals(2L,owner.bufferStorage().snapshot().get("devicePreferredAllocations"));
                assertEquals(0L,owner.bufferStorage().snapshot().get("liveDevicePreferredBytes"));
            }
        } finally { owner.close(); }
        assertEquals(0L,storageStatistics.snapshot().get("liveHostAccessibleBytes"));
    }

    @Test void rejectedBatchReleasesPrivateInitialStagingAndClosedTargetThenRecovers() {
        var owner=device();
        try {
            var target=owner.createBuffer(()->"cancelled static data",GpuBuffer.USAGE_COPY_SRC,
                    ByteBuffer.allocateDirect(32));
            target.close();
            owner.queue().enqueue(new OverhaulGpuOperation() {
                @Override public void record(VkCommandBuffer command) { throw new IllegalStateException("deliberate rollback"); }
                @Override public void close() {}
            });
            assertThrows(IllegalStateException.class,()->owner.queue().flush());
            assertThrows(IllegalStateException.class,()->owner.buffer(target));
            assertEquals(0L,owner.bufferStorage().snapshot().get("liveDevicePreferredBytes"));
            assertEquals(0L,owner.bufferStorage().snapshot().get("liveHostAccessibleBytes"));
            try (var source=owner.createBuffer(()->"recovery",GpuBuffer.USAGE_COPY_SRC,
                         ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder()).putInt(0,0x12345678));
                 var output=owner.createBuffer(()->"output",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,4)) {
                owner.createCommandEncoder().copyToBuffer(source.slice(),output.slice());
                try (var mapped=output.map(0,4,true,false)) { assertEquals(0x12345678,mapped.data().getInt(0)); }
            }
        } finally { owner.close(); }
    }

    private static OverhaulDeviceBackend device() {
        return new OverhaulDeviceBackend(VulkanContext.openHeadless(),new GpuDebugOptions(0,false,false,false));
    }
}
