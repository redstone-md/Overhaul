package dev.overhaul.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/** Reusable indexed-indirect/instanced batches with independent per-flight attachments and uniforms. */
public final class AsyncRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final GpuAllocator allocator;
    private final FrameRing ring;
    private final Target[] targets;
    private final Set<GpuBatch> batches = Collections.newSetFromMap(new IdentityHashMap<>());
    private IndexedPipeline pipeline;
    private final boolean allowMultiDraw;
    private boolean closed;

    public AsyncRenderer(VulkanContext context, int width, int height, int framesInFlight) {
        this(context, width, height, framesInFlight, DrawMode.AUTOMATIC);
    }

    public AsyncRenderer(VulkanContext context, int width, int height, int framesInFlight, DrawMode mode) {
        this.context = context;
        allowMultiDraw = java.util.Objects.requireNonNull(mode, "mode") == DrawMode.AUTOMATIC;
        allocator = new GpuAllocator(context);
        FrameRing frames = null;
        targets = new Target[framesInFlight > 0 && framesInFlight <= 8 ? framesInFlight : 0];
        try {
            frames = new FrameRing(context, framesInFlight);
            ring = frames;
            for (int i = 0; i < targets.length; i++) targets[i] = new Target(width, height);
            pipeline = new IndexedPipeline(context, targets[0].color.renderPass, targets[0].camera.layout, width, height);
        } catch (RuntimeException | Error failure) {
            for (Target target : targets) if (target != null) target.close();
            if (frames != null) frames.close();
            allocator.close();
            throw failure;
        }
    }

    /** Copies the CPU packet immediately; its slab storage may be released as soon as this returns. */
    public GpuBatch uploadBatch(MeshBatch batch) {
        requireOpen();
        boolean multiDraw = allowMultiDraw && context.multiDrawIndirect() && context.indirectFirstInstance();
        GpuBatch gpu = new GpuBatch(this, allocator, batch, multiDraw);
        GpuBuffer staging = null;
        try {
            staging = allocator.hostBuffer(batch.byteSize(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            ByteBuffer packet = batch.bytes();
            if (!multiDraw) {
                ByteBuffer patched = org.lwjgl.system.MemoryUtil.memAlloc(packet.remaining()).order(ByteOrder.nativeOrder());
                try {
                    patched.put(packet).flip();
                    for (int i = 0; i < batch.commandCount; i++) patched.putInt(batch.commandOffset() + i * 20 + 16, 0);
                    staging.write(patched);
                } finally { org.lwjgl.system.MemoryUtil.memFree(patched); }
            } else staging.write(packet);
            GpuBuffer transfer = staging;
            gpu.lastUse = ring.submit(frame -> {
                frame.retireAfterFence(transfer);
                copy(frame.command(), transfer, gpu.vertices, 0);
                copy(frame.command(), transfer, gpu.indices, batch.vertexBytes);
                copy(frame.command(), transfer, gpu.instances, batch.vertexBytes + batch.indexBytes);
                copy(frame.command(), transfer, gpu.indirect, batch.commandOffset());
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                    vkCmdPipelineBarrier(frame.command(), VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, barrier, null, null);
                }
            });
            batches.add(gpu);
            return gpu;
        } catch (RuntimeException | Error failure) {
            if (staging != null) staging.close();
            gpu.destroy();
            throw failure;
        }
    }

    private static void copy(VkCommandBuffer command, GpuBuffer staging, GpuBuffer destination, long offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdCopyBuffer(command, staging.handle(), destination.handle(),
                VkBufferCopy.calloc(1, stack).srcOffset(offset).size(destination.size()));
        }
    }

    public RenderedFrame submit(GpuBatch batch, float[] cameraTransform) {
        return submit(batch, cameraTransform, false);
    }

    public RenderedFrame submitForReadback(GpuBatch batch, float[] cameraTransform) {
        return submit(batch, cameraTransform, true);
    }

    private RenderedFrame submit(GpuBatch batch, float[] transform, boolean capture) {
        requireOpen();
        if (!batches.contains(batch) || batch.retired) throw new IllegalArgumentException("batch is retired or belongs to another renderer");
        FrameRing.Submission submission = ring.submit(frame -> {
            Target target = targets[frame.slot()];
            target.awaitPresentation();
            target.camera.write(transform);
            IndexedPass.begin(frame.command(), target.color, target.camera, pipeline);
            recordDraws(frame.command(), batch);
            vkCmdEndRenderPass(frame.command());
            if (capture) {
                if (target.readback == null) target.readback = allocator.hostBuffer(
                    (long) target.color.width * target.color.height * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                target.color.copyTo(frame.command(), target.readback);
            }
        });
        batch.lastUse = submission;
        targets[submission.slot()].sequence = submission.sequence();
        return new RenderedFrame(submission, capture);
    }

    private void recordDraws(VkCommandBuffer command, GpuBatch batch) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindIndexBuffer(command, batch.indices.handle(), 0, VK_INDEX_TYPE_UINT32);
            vkCmdBindVertexBuffers(command, 0, stack.longs(batch.vertices.handle(), batch.instances.handle()), stack.longs(0, 0));
            if (batch.multiDraw) {
                int maximum = Math.max(1, context.maxIndirectDrawCount());
                for (int start = 0; start < batch.commandCount; ) {
                    int count = Math.min(maximum, batch.commandCount - start);
                    vkCmdDrawIndexedIndirect(command, batch.indirect.handle(), (long) start * 20, count, 20);
                    start += count;
                }
            } else {
                for (int i = 0; i < batch.commandCount; i++) {
                    vkCmdBindVertexBuffers(command, 1, stack.longs(batch.instances.handle()),
                        stack.longs((long) batch.firstInstances[i] * 16));
                    vkCmdDrawIndexedIndirect(command, batch.indirect.handle(), (long) i * 20, 1, 20);
                }
            }
        }
    }

    void release(GpuBatch batch, boolean defer) {
        context.requireOwner();
        if (batch.retired) return;
        requireOpen();
        if (!batches.remove(batch)) throw new IllegalArgumentException("batch belongs to another renderer");
        batch.retired = true;
        if (defer) batch.lastUse.retireAfterFence(batch::destroy);
        else { batch.lastUse.await(); batch.destroy(); }
    }

    public void poll() { requireOpen(); ring.poll(); }
    public void drain() { requireOpen(); ring.drain(); }
    public enum DrawMode { AUTOMATIC, SINGLE_DRAW }

    private void requireOpen() {
        context.requireOwner();
        if (closed) throw new IllegalStateException("asynchronous renderer closed");
    }

    @Override public void close() {
        if (closed) return;
        requireOpen();
        ring.drain();
        for (GpuBatch batch : batches.toArray(new GpuBatch[0])) batch.close();
        ring.close();
        pipeline.close();
        for (Target target : targets) target.close();
        allocator.close();
        closed = true;
    }

    /** Readback is valid until this slot is reused; submit() without capture never copies to the CPU. */
    public final class RenderedFrame {
        private final FrameRing.Submission submission;
        private final boolean capture;
        private RenderedFrame(FrameRing.Submission submission, boolean capture) { this.submission = submission; this.capture = capture; }
        public long sequence() { return submission.sequence(); }
        public void await() { requireOpen(); submission.await(); }
        public boolean isComplete() { requireOpen(); return submission.isComplete(); }
        /** Queues a GPU blit and presentation without waiting for the render fence on this call. */
        public boolean presentTo(SwapchainPresenter presenter) {
            Target target = currentTarget();
            return presenter.presentImage(context, target.color, completion -> target.presentation = completion);
        }

        private Target currentTarget() {
            requireOpen();
            Target target = targets[submission.slot()];
            if (target.sequence != submission.sequence()) throw new IllegalStateException("rendered frame slot has been reused");
            return target;
        }

        public ByteBuffer pixels() {
            Target target = currentTarget();
            if (!capture) throw new IllegalStateException("frame was submitted without readback");
            submission.await();
            return target.readback.read();
        }
    }

    private final class Target implements AutoCloseable {
        private final ColorTarget color;
        private final CameraUniform camera;
        private GpuBuffer readback;
        private long sequence;
        private FrameRing.Submission presentation;
        private Target(int width, int height) {
            color = new ColorTarget(context, width, height);
            try { camera = new CameraUniform(context, allocator); }
            catch (RuntimeException | Error failure) { color.close(); throw failure; }
        }
        private void awaitPresentation() {
            if (presentation != null) { presentation.await(); presentation = null; }
        }
        @Override public void close() {
            awaitPresentation();
            if (readback != null) readback.close();
            camera.close(); color.close();
        }
    }
}
