package dev.overhaul.vulkan;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

@Tag("native")
class GpuBufferRangeTest {
    @Test void partialMappedWritesReachGpuWithoutChangingOtherBytesOrSourceView() {
        byte[] expected=new byte[64];
        for(int i=0;i<expected.length;i++)expected[i]=(byte)(i*7+13);
        byte[] patchBytes=new byte[32];Arrays.fill(patchBytes,(byte)0xE7);
        for(int i=5;i<17;i++)patchBytes[i]=(byte)(211-i);
        ByteBuffer patch=ByteBuffer.wrap(patchBytes).asReadOnlyBuffer();patch.position(5).limit(17).mark();
        try(VulkanContext context=VulkanContext.openHeadless(VulkanContext.validationAvailable());
            GpuAllocator allocator=new GpuAllocator(context);ImmediateCommands commands=new ImmediateCommands(context);
            GpuBuffer source=allocator.hostBuffer(64,VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            GpuBuffer device=allocator.deviceBuffer(64,VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            GpuBuffer readback=allocator.hostBuffer(64,VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            source.write(ByteBuffer.wrap(expected));
            source.write(13,patch);
            assertEquals(5,patch.position());assertEquals(17,patch.limit());assertEquals(5,patch.reset().position());
            System.arraycopy(patchBytes,5,expected,13,12);
            assertEquals(76,source.hostWrittenBytes());
            assertGpuBytes(commands,source,device,readback,expected);

            assertThrows(IllegalArgumentException.class,()->source.write(-1,patch));
            assertThrows(IllegalArgumentException.class,()->source.write(Integer.MAX_VALUE-4,patch));
            assertThrows(IllegalArgumentException.class,()->source.write(53,patch));
            assertThrows(IllegalArgumentException.class,()->source.write(65,ByteBuffer.allocate(0)));
            assertThrows(IllegalArgumentException.class,()->source.write(ByteBuffer.allocate(65)));
            assertThrows(IllegalStateException.class,()->device.write(1,patch));
            source.write(64,ByteBuffer.allocate(0));
            assertEquals(76,source.hostWrittenBytes(),"Rejected and empty writes must not touch mapped bytes");
            assertGpuBytes(commands,source,device,readback,expected);

            source.write(52,patch); // Exact allocation-end boundary with a nonzero source position.
            System.arraycopy(patchBytes,5,expected,52,12);
            assertGpuBytes(commands,source,device,readback,expected);
            assertEquals(5,patch.position());assertEquals(17,patch.limit());
            context.assertValidationClean();
        }
    }

    private static void assertGpuBytes(ImmediateCommands commands,GpuBuffer source,GpuBuffer device,GpuBuffer readback,byte[] expected){
        commands.copy(source,device,expected.length);commands.copy(device,readback,expected.length);
        byte[] received=new byte[expected.length];readback.read().get(received);
        assertArrayEquals(expected,received,"Partial write must preserve the complete prefix and suffix through a device-local roundtrip");
    }
}
