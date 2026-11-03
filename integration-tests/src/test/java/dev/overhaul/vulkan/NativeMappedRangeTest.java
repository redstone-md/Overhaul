package dev.overhaul.vulkan;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

@Tag("native")
class NativeMappedRangeTest {
    @Test void directNativeLeasesPinStorageFlushWritesAndInvalidateReadback(){
        try(VulkanContext context=VulkanContext.openHeadless();GpuAllocator allocator=new GpuAllocator(context);ImmediateCommands commands=new ImmediateCommands(context);
            GpuBuffer source=allocator.hostBuffer(64,VK_BUFFER_USAGE_TRANSFER_SRC_BIT);GpuBuffer target=allocator.hostBuffer(64,VK_BUFFER_USAGE_TRANSFER_DST_BIT)){
            source.write(ByteBuffer.allocate(64));
            GpuBuffer.MappedRange range=source.mapRange(7,13,false,true);
            try(range){
                ByteBuffer bytes=range.buffer();assertTrue(bytes.isDirect());assertEquals(13,bytes.remaining());
                assertTrue(org.lwjgl.system.MemoryUtil.memAddress(bytes)!=0);
                while(bytes.hasRemaining())bytes.put((byte)0x53);
                assertThrows(IllegalStateException.class,source::close);
                assertThrows(IllegalStateException.class,()->source.mapRange(10,1,true,false));
                try(GpuBuffer.MappedRange sibling=source.mapRange(32,4,false,true)){sibling.buffer().putInt(0x01020304);}
            }
            assertThrows(IllegalStateException.class,range::buffer);
            commands.copy(source,target,64);
            try(GpuBuffer.MappedRange read=target.mapRange(0,64,true,false)){
                assertTrue(read.buffer().isReadOnly());
                for(int i=0;i<32;i++)assertEquals(i>=7&&i<20?0x53:0,read.buffer().get(i)&255);
                assertEquals(0x01020304,read.buffer().getInt(32));
                for(int i=36;i<64;i++)assertEquals(0,read.buffer().get(i));
            }
            assertThrows(IllegalArgumentException.class,()->source.mapRange(Integer.MAX_VALUE,2,true,false));
            assertThrows(IllegalArgumentException.class,()->source.mapRange(0,1,false,false));
        }
    }
}
