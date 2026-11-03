package dev.overhaul.vulkan;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkEventCreateInfo;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.overhaul.vulkan.VulkanException.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class TimedSubmissionNativeTest {
    @Test void timeoutNeverRetiresPendingResourcesAndHostReleaseCompletesExactlyOnce(){
        try(VulkanContext context=VulkanContext.openHeadless(VulkanContext.validationAvailable())){
            long event;
            try(MemoryStack stack=MemoryStack.stackPush()){
                LongBuffer handle=stack.mallocLong(1);
                check(vkCreateEvent(context.device(),VkEventCreateInfo.calloc(stack).sType$Default(),null,handle),"test event");
                event=handle.get(0);
            }
            AtomicInteger retired=new AtomicInteger();FrameRing.Submission receipt;
            try{
                try(FrameRing ring=new FrameRing(context,2)){
                    receipt=ring.submit(frame->{
                        frame.retireAfterFence(retired::incrementAndGet);
                        try(MemoryStack stack=MemoryStack.stackPush()){
                            vkCmdWaitEvents(frame.command(),stack.longs(event),VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,null,null,null);
                        }
                    });
                    try{
                        assertEquals(0,ring.blockingWaitCalls());
                        assertFalse(receipt.await(0),"An unsignaled native event must hold the real submit fence");
                        assertEquals(0,ring.blockingWaitCalls(),"Zero-time polls must not count as blocking-wait invocations");
                        assertFalse(receipt.await(1_000_000));
                        assertEquals(1,ring.blockingWaitCalls());
                        assertEquals(0,retired.get(),"Timed-out work must keep its resource retirement pending");
                        assertThrows(IllegalArgumentException.class,()->receipt.await(-2));
                    }finally{check(vkSetEvent(context.device(),event),"release test event");}
                    assertTrue(receipt.await(5_000_000_000L));assertEquals(1,retired.get());
                    assertEquals(2,ring.blockingWaitCalls());
                    assertTrue(receipt.await(0));receipt.await();assertEquals(1,retired.get());
                    assertEquals(2,ring.blockingWaitCalls(),"An already-retired receipt must not invoke the native wait again");
                    ring.submit(frame->frame.retireAfterFence(retired::incrementAndGet)).await();assertEquals(2,retired.get());
                }
                assertTrue(receipt.await(0),"Completed receipts remain readable after ring destruction");
                assertEquals(2,retired.get());context.assertValidationClean();
            }finally{vkDestroyEvent(context.device(),event,null);}
        }
    }

    @Test void slotReuseCloseAndImmediateLaneCountersCountCallsRatherThanAssumedStalls(){
        try(VulkanContext context=VulkanContext.openHeadless();FrameRing ring=new FrameRing(context,1);
            ImmediateCommands immediate=new ImmediateCommands(context)){
            ring.submit(frame->{});assertEquals(0,ring.blockingWaitCalls());
            FrameRing.Submission second=ring.submit(frame->{});
            assertEquals(1,ring.blockingWaitCalls(),"Reusing a pending slot must account for its native wait even if the GPU already finished");
            second.await();assertEquals(2,ring.blockingWaitCalls());
            ring.submit(frame->{});ring.close();assertEquals(3,ring.blockingWaitCalls(),"Ring close must account for draining a pending slot");
            assertThrows(IllegalArgumentException.class,()->immediate.submit(command->{throw new IllegalArgumentException("record failed");}));
            assertEquals(0,immediate.submissionCount());assertEquals(0,immediate.fenceWaitCalls());
            immediate.submit(command->{});assertEquals(1,immediate.submissionCount());assertEquals(1,immediate.fenceWaitCalls());
            assertThrows(IllegalArgumentException.class,()->immediate.submit(command->{throw new IllegalArgumentException("second recording failed");}));
            assertEquals(1,immediate.submissionCount());assertEquals(1,immediate.fenceWaitCalls());
            immediate.submit(command->{});immediate.close();
            assertEquals(2,immediate.submissionCount());assertEquals(2,immediate.fenceWaitCalls());
        }
    }
}
