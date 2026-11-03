package dev.overhaul.vulkan;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

@Tag("native")
class NativeImageTest {
    private static final int COLOR_USAGE=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    @Test void pitchedMipArrayUploadsCopiesAndOwnershipAreRealNativeOperations(){
        try(VulkanContext context=VulkanContext.openHeadless(VulkanContext.validationAvailable());
            GpuAllocator allocator=new GpuAllocator(context);ImmediateCommands commands=new ImmediateCommands(context)){
            GpuImage image=allocator.createImage(ImageFormat.RGBA8_UNORM,8,6,3,2,COLOR_USAGE);
            GpuImage target=allocator.createImage(ImageFormat.RGBA8_UNORM,8,6,3,2,COLOR_USAGE);
            GpuSampler sampler=allocator.createSampler(SamplerSettings.nearest());
            GpuImageView first=image.view(0,3,0,2),sibling=image.view(1,1,1,1);
            assertTrue(sampler.handle()!=0);first.close();assertTrue(sibling.handle()!=0);
            commands.clearColor(image,1,1,0,1,0,1);
            ByteBuffer input=ByteBuffer.allocate(32);input.position(4);
            input.put(new byte[]{-1,10,20,-1, 30,40,50,-1});
            for(int i=0;i<8;i++)input.put((byte)0x7f);
            input.put(new byte[]{60,70,80,-1, 90,100,110,-1}).flip().position(4).mark();
            ImageRegion patch=new ImageRegion(1,1,1,1,2,2);
            commands.upload(image,patch,input,16);
            assertEquals(4,input.position());assertEquals(28,input.limit());assertEquals(4,input.reset().position());
            ByteBuffer pixels=commands.readback(image,ImageRegion.whole(image,1,1),0);
            pixel(pixels,4,0,0,0,255,0,255);pixel(pixels,4,1,1,255,10,20,255);pixel(pixels,4,2,2,90,100,110,255);
            ByteBuffer pitched=commands.readback(image,patch,16);
            assertEquals(24,pitched.remaining());for(int i=8;i<16;i++)assertEquals(0,pitched.get(i),"row padding must be initialized");
            commands.clearColor(target,1,0,0,0,1,1);
            commands.copyImage(image,patch,target,new ImageRegion(1,0,0,0,2,2));
            ByteBuffer copied=commands.readback(target,ImageRegion.whole(target,1,0),0);
            pixel(copied,4,0,0,255,10,20,255);pixel(copied,4,1,1,90,100,110,255);pixel(copied,4,3,2,0,0,255,255);
            commands.copyImage(target,new ImageRegion(1,0,0,0,2,2),target,new ImageRegion(1,0,1,1,2,2));
            ByteBuffer moved=commands.readback(target,ImageRegion.whole(target,1,0),0);
            pixel(moved,4,1,1,255,10,20,255);pixel(moved,4,2,2,90,100,110,255);pixel(moved,4,3,2,0,0,255,255);
            assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,image.layout(1,1));assertEquals(VK_IMAGE_LAYOUT_UNDEFINED,image.layout(0,0));
            assertThrows(IllegalArgumentException.class,()->commands.upload(image,patch,input,6));
            assertThrows(IllegalArgumentException.class,()->commands.readback(image,new ImageRegion(1,1,4,0,1,1),0));
            assertThrows(IllegalArgumentException.class,()->image.view(0,4,0,1));
            int before=image.layout(1,1);
            assertThrows(IllegalArgumentException.class,()->commands.submit(command->{
                try(GpuImage.LayoutChange change=image.recordTransition(command,1,1,1,1,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)){
                    throw new IllegalArgumentException("cancel native recording");
                }
            }));
            assertEquals(before,image.layout(1,1));
            pixel(commands.readback(image,ImageRegion.whole(image,1,1),0),4,1,1,255,10,20,255);
            image.close();assertThrows(IllegalStateException.class,sibling::handle);
            allocator.close();assertThrows(IllegalStateException.class,target::handle);assertThrows(IllegalStateException.class,sampler::handle);
            context.assertValidationClean();
        }
    }

    @Test void cubeFacesDepthAndIntegerClearsRespectFormatAndSubresourceLimits(){
        try(VulkanContext context=VulkanContext.openHeadless(VulkanContext.validationAvailable());GpuAllocator allocator=new GpuAllocator(context);
            ImmediateCommands commands=new ImmediateCommands(context)){
            try(GpuImage cube=allocator.createImage(ImageFormat.RGBA8_UNORM,4,4,3,6,COLOR_USAGE,VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT);
                GpuImageView view=cube.cubeView(0,3,0)){
                assertTrue(view.handle()!=0);commands.clearColor(cube,2,5,1,0,0,1);
                pixel(commands.readback(cube,ImageRegion.whole(cube,2,5),0),1,0,0,255,0,0,255);
                assertEquals(VK_IMAGE_LAYOUT_UNDEFINED,cube.layout(2,0));
            }
            int depthUsage=VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            try(GpuImage depth=allocator.createImage(ImageFormat.D32_SFLOAT,4,4,2,2,depthUsage)){
                commands.clearDepth(depth,1,1,.25f);ByteBuffer values=commands.readback(depth,ImageRegion.whole(depth,1,1),0).order(ByteOrder.nativeOrder());
                while(values.hasRemaining())assertEquals(.25f,values.getFloat(),0);
                assertThrows(IllegalArgumentException.class,()->commands.clearColor(depth,0,0,1,0,0,1));
            }
            try(GpuImage integer=allocator.createImage(ImageFormat.R8_UINT,2,2,1,1,VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT)){
                commands.clearColor(integer,0,0,211,0,0,0);ByteBuffer values=commands.readback(integer,ImageRegion.whole(integer,0,0),0);
                while(values.hasRemaining())assertEquals(211,values.get()&255);
            }
            assertThrows(IllegalArgumentException.class,()->allocator.createImage(ImageFormat.RGBA8_UNORM,4,3,1,6,COLOR_USAGE,VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT));
            context.assertValidationClean();
        }
    }

    private static void pixel(ByteBuffer image,int width,int x,int y,int... channels){for(int i=0;i<4;i++)assertEquals(channels[i],image.get((y*width+x)*4+i)&255);}
}
