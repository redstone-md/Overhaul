package dev.overhaul.vulkan;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/** Opt-in real-device checks for queue order, CPU snapshot ownership and layout rollback. */
@Tag("native")
class ImageCommandNativeTest {
    private static final int TRANSFERS=VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    @Test void deferredPitchedUploadAndOverlappingCopyShareOneFence() {
        var context=VulkanContext.openHeadless(VulkanContext.validationAvailable());
        try (context; var allocator=new GpuAllocator(context);
             var image=allocator.createImage(ImageFormat.RGBA8_UNORM,3,2,1,1,TRANSFERS);
             var copy=allocator.createImage(ImageFormat.RGBA8_UNORM,3,2,1,1,TRANSFERS);
             var readback=allocator.hostBuffer(32,VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             var frames=new FrameRing(context,2)) {
            ByteBuffer pixels=ByteBuffer.allocate(28),padding=ByteBuffer.allocate(32);
            for (int index=0;index<28;index++) pixels.put(index,(byte)(index+11));
            for (int index=0;index<32;index++) padding.put(index,(byte)0x55);
            readback.write(padding);
            var whole=ImageRegion.whole(image,0,0);
            var upload=ImageCommand.upload(context,image,whole,pixels,16);
            var clone=ImageCommand.copy(context,image,whole,copy,whole);
            var overlap=ImageCommand.copy(context,copy,new ImageRegion(0,0,0,0,2,2),copy,new ImageRegion(0,0,1,0,2,2));
            var read=ImageCommand.copyImageToBuffer(context,copy,whole,readback,0,20);
            List<ImageCommand> commands=List.of(upload,clone,overlap,read);
            assertEquals(44,commands.stream().mapToLong(ImageCommand::stagingBytes).sum());
            // Recycle caller storage before any native recording.
            for (int index=0;index<28;index++) pixels.put(index,(byte)0);
            var submission=frames.submit(frame->{
                for (var command:commands) command.record(frame.command());
                frame.retireAfterFence(()->closeReverse(commands));
            });
            for (var command:commands) command.complete();
            assertEquals(VK_IMAGE_LAYOUT_GENERAL,image.layout(0,0));
            submission.await();
            ByteBuffer actual=readback.read();
            for (int row=0;row<2;row++) for (int x=0;x<3;x++) for (int channel=0;channel<4;channel++) {
                int originalX=x==0?0:x-1;
                assertEquals((byte)(row*16+originalX*4+channel+11),actual.get(row*20+x*4+channel));
            }
            for (int index=12;index<20;index++) assertEquals((byte)0x55,actual.get(index));
            assertThrows(IllegalStateException.class,upload::complete,"staging command must retire only after the fence");
        }
        context.assertValidationClean();
    }

    @Test void rejectedBatchRollsBackLayoutProposalsInReverseOrder() {
        var context=VulkanContext.openHeadless(VulkanContext.validationAvailable());
        try (context; var allocator=new GpuAllocator(context);
             var image=allocator.createImage(ImageFormat.RGBA8_UNORM,2,2,1,1,TRANSFERS);
             var frames=new FrameRing(context,2)) {
            var clear=ImageCommand.clearColor(context,image,0,0,1,0,0,1);
            var transition=ImageCommand.transition(context,image,0,0,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            try {
                assertThrows(IllegalStateException.class,()->frames.submit(frame->{
                    clear.record(frame.command()); transition.record(frame.command());
                    throw new IllegalStateException("deliberate batch rejection");
                }));
                assertEquals(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,image.layout(0,0));
            } finally { transition.close(); clear.close(); }
            assertEquals(VK_IMAGE_LAYOUT_UNDEFINED,image.layout(0,0));
            assertThrows(IllegalArgumentException.class,()->ImageCommand.clearColor(context,image,0,1,0,0,0,0));
            // A rejected recording leaves the ring reusable, and the synchronous facade uses the same command path.
            try (var immediate=new ImmediateCommands(context)) {
                immediate.clearColor(image,0,0,0,1,0,1);
                var pixels=immediate.readback(image,ImageRegion.whole(image,0,0),0);
                for (int pixel=0;pixel<4;pixel++) assertEquals(255,Byte.toUnsignedInt(pixels.get(pixel*4+1)));
            }
        }
        context.assertValidationClean();
    }

    private static void closeReverse(List<ImageCommand> commands) {
        for (int index=commands.size()-1;index>=0;index--) commands.get(index).close();
    }
}
