package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.lwjgl.vulkan.VK10.*;

/** Native checks for command-ring wrap, deferred lifetimes, instancing, and failure recovery. */
final class AsyncSmoke {
    private AsyncSmoke() { }

    static void verify(VulkanContext context) {
        verifyRing(context);
        verifyBatches(context, AsyncRenderer.DrawMode.AUTOMATIC);
        verifyBatches(context, AsyncRenderer.DrawMode.SINGLE_DRAW);
    }

    private static void verifyRing(VulkanContext context) {
        AtomicInteger retired = new AtomicInteger();
        try (GpuAllocator allocator = new GpuAllocator(context)) {
            List<GpuBuffer> destinations = new ArrayList<>();
            try (FrameRing ring = new FrameRing(context, 3)) {
                for (int i = 0; i < 11; i++) {
                    GpuBuffer staging = allocator.hostBuffer(256, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                    ByteBuffer pattern = ByteBuffer.allocate(256);
                    for (int j = 0; j < pattern.capacity(); j++) pattern.put((byte) (i * 17 + j));
                    staging.write(pattern.flip());
                    GpuBuffer destination = allocator.hostBuffer(256, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                    destinations.add(destination);
                    FrameRing.Submission submission = ring.submit(frame -> {
                        frame.retireAfterFence(() -> { staging.close(); retired.incrementAndGet(); });
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            vkCmdCopyBuffer(frame.command(), staging.handle(), destination.handle(), VkBufferCopy.calloc(1, stack).size(256));
                            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                            vkCmdPipelineBarrier(frame.command(), VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                                0, barrier, null, null);
                        }
                    });
                    if (submission.sequence() != i + 1) throw new IllegalStateException("submission sequence mismatch");
                    if (i < 3 && retired.get() != 0) throw new IllegalStateException("staging retired before its slot fence was consumed");
                }
                ring.drain();
                if (retired.get() != 11) throw new IllegalStateException("not all staging allocations retired");
                for (int i = 0; i < destinations.size(); i++) {
                    ByteBuffer received = destinations.get(i).read();
                    for (int j = 0; j < received.remaining(); j++)
                        if (received.get(j) != (byte) (i * 17 + j)) throw new IllegalStateException("frame-ring transfer differs");
                }
                AtomicInteger rolledBack = new AtomicInteger();
                try {
                    ring.submit(frame -> {
                        frame.retireAfterFence(rolledBack::incrementAndGet);
                        throw new IllegalArgumentException("intentional recording failure");
                    });
                    throw new IllegalStateException("recording failure swallowed");
                } catch (IllegalArgumentException expected) { }
                if (rolledBack.get() != 1) throw new IllegalStateException("failed recording cleanup differs");
                ring.submit(frame -> frame.retireAfterFence(retired::incrementAndGet));
            }
            if (retired.get() != 12) throw new IllegalStateException("ring close did not retire pending work");
            for (GpuBuffer destination : destinations) destination.close();
        }
        System.out.println("Frame ring: 11 transfers across 3 slots verified; fence retirement, rollback and close verified");
    }

    private static void verifyBatches(VulkanContext context, AsyncRenderer.DrawMode mode) {
        ByteBuffer vertices = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        vertex(vertices, -0.20f, -0.3f); vertex(vertices, 0.20f, -0.3f); vertex(vertices, 0, 0.3f);
        vertices.flip();
        ByteBuffer indices = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1).putInt(2).flip();
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        try (AsyncRenderer renderer = new AsyncRenderer(context, 128, 128, 3, mode)) {
            GpuBatch gpu;
            try (MeshBatch packet = MeshBatch.builder()
                .add(vertices, indices, 3, new float[] {-0.5f, 0, 0, 0.5f, 0, 0})
                .add(vertices, indices, 3, new float[] {0, 0.5f, 0}).build()) {
                gpu = renderer.uploadBatch(packet);
            }
            AsyncRenderer.RenderedFrame first = null, latest = null;
            for (int i = 0; i < 10; i++) {
                latest = renderer.submitForReadback(gpu, identity);
                if (first == null) first = latest;
            }
            ByteBuffer pixels = latest.pixels();
            try { renderer.submit(gpu, new float[] {1}); throw new IllegalStateException("invalid camera accepted"); }
            catch (IllegalArgumentException expected) { }
            int left = (64 * 128 + 32) * 4, right = (64 * 128 + 96) * 4, bottom = (96 * 128 + 64) * 4;
            if (Byte.toUnsignedInt(pixels.get(left)) <= 20 || Byte.toUnsignedInt(pixels.get(right)) <= 20
                || Byte.toUnsignedInt(pixels.get(bottom)) <= 20) throw new IllegalStateException("instanced batch lost draw/instance offsets");
            try { first.pixels(); throw new IllegalStateException("stale readback accepted"); }
            catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("reused")) throw expected;
            }
            AsyncRenderer.RenderedFrame uncaptured = renderer.submit(gpu, identity);
            try { uncaptured.pixels(); throw new IllegalStateException("uncaptured pixels accepted"); }
            catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("without readback")) throw expected;
            }
            gpu.retire();
            try { renderer.submit(gpu, identity); throw new IllegalStateException("retired batch accepted"); }
            catch (IllegalArgumentException expected) { }
            renderer.drain();
            gpu.close();
            System.out.println("Asynchronous indexed batch: 2 draws, 3 translated instances, 10 frames/3 slots; multiDraw=" + gpu.usesMultiDraw());
        }
    }

    private static void vertex(ByteBuffer vertices, float x, float y) {
        vertices.putFloat(x).putFloat(y).putFloat(0.5f).putInt(3);
    }
}
