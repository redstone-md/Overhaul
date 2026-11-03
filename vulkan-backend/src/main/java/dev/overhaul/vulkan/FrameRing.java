package dev.overhaul.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static dev.overhaul.vulkan.VulkanException.check;

/** Bounded frames in flight. A slot's command storage and retired allocations survive its fence. */
public final class FrameRing implements AutoCloseable {
    private final VulkanContext context;
    private final Slot[] slots;
    private long submitted;
    private long completed;
    private boolean recording;
    private boolean retiring;
    private boolean closed;

    public FrameRing(VulkanContext context, int framesInFlight) {
        if (framesInFlight < 1 || framesInFlight > 8) throw new IllegalArgumentException("frames in flight must be 1..8");
        this.context = Objects.requireNonNull(context, "context");
        slots = new Slot[framesInFlight];
        try {
            for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    public Submission submit(Consumer<Frame> recorder) {
        requireOpen();
        if (recording || retiring) throw new IllegalStateException("nested frame submission or retirement");
        Objects.requireNonNull(recorder, "recorder");
        int index = (int) (submitted % slots.length);
        Slot slot = slots[index];
        awaitSlot(slot);
        Frame frame = new Frame(index, slot.command);
        recording = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandPool(context.device(), slot.pool, 0), "frame command pool reset");
            check(vkBeginCommandBuffer(slot.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "frame command begin");
            recorder.accept(frame);
            frame.active = false;
            if (frame.cancelled) {
                if (!frame.retirements.isEmpty()) throw new IllegalStateException("cancelled frame cannot retire resources");
                check(vkResetCommandPool(context.device(), slot.pool, 0), "cancelled frame reset");
                return null;
            }
            slot.retirements = frame.retirements;
            check(vkEndCommandBuffer(slot.command), "frame command end");
            check(vkResetFences(context.device(), slot.fence), "frame fence reset");
            VkSubmitInfo info = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(slot.command));
            if (frame.waitSemaphore != 0) info.waitSemaphoreCount(1).pWaitSemaphores(stack.longs(frame.waitSemaphore))
                .pWaitDstStageMask(stack.ints(frame.waitStage));
            if (frame.signalSemaphore != 0) info.pSignalSemaphores(stack.longs(frame.signalSemaphore));
            check(vkQueueSubmit(context.queue(), info, slot.fence), "frame queue submit");
            slot.pending = true;
            slot.sequence = ++submitted;
            return new Submission(index, slot.sequence);
        } catch (RuntimeException | Error failure) {
            frame.active = false;
            // Unsubmitted resources may also have appeared in earlier queue work. Failure is a slow path.
            try { context.waitIdle(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            try { retire(frame.retirements); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally { recording = false; }
    }

    public void poll() {
        requireOpen();
        if (retiring) throw new IllegalStateException("cannot poll during retirement");
        for (Slot slot : slots) {
            if (!slot.pending) continue;
            int result = vkGetFenceStatus(context.device(), slot.fence);
            if (result == VK_SUCCESS) complete(slot);
            else if (result != VK_NOT_READY) check(result, "frame fence status");
        }
    }

    public void drain() {
        requireOpen();
        if (recording || retiring) throw new IllegalStateException("cannot drain during recording or retirement");
        for (Slot slot : slots) awaitSlot(slot);
    }

    public int capacity() { return slots.length; }

    private void awaitSlot(Slot slot) {
        if (!slot.pending) return;
        check(vkWaitForFences(context.device(), slot.fence, true, -1L), "frame fence wait");
        complete(slot);
    }

    private void complete(Slot slot) {
        slot.pending = false;
        completed = Math.max(completed, slot.sequence);
        retiring = true;
        try { retire(slot.retirements); }
        finally { retiring = false; }
    }

    private static void retire(List<AutoCloseable> resources) {
        RuntimeException failure = null;
        for (AutoCloseable resource : resources) {
            try { resource.close(); }
            catch (Exception error) {
                if (failure == null) failure = new IllegalStateException("GPU resource retirement failed", error);
                else failure.addSuppressed(error);
            }
        }
        resources.clear();
        if (failure != null) throw failure;
    }

    private void requireOpen() {
        context.requireOwner();
        if (closed) throw new IllegalStateException("frame ring closed");
    }

    @Override public void close() {
        if (closed) return;
        context.requireOwner();
        if (recording || retiring) throw new IllegalStateException("cannot close during recording or retirement");
        RuntimeException failure = null;
        for (Slot slot : slots) {
            if (slot == null) continue;
            try { awaitSlot(slot); }
            catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            } finally { slot.close(); }
        }
        closed = true;
        if (failure != null) throw failure;
    }

    public final class Frame {
        private final int slot;
        private final VkCommandBuffer command;
        private final List<AutoCloseable> retirements = new ArrayList<>();
        private boolean active = true;
        private boolean cancelled;
        private long waitSemaphore;
        private int waitStage;
        private long signalSemaphore;

        private Frame(int slot, VkCommandBuffer command) { this.slot = slot; this.command = command; }
        public int slot() { requireActive(); return slot; }
        public VkCommandBuffer command() { requireActive(); return command; }
        /** The caller owns semaphore lifetime until the submission fence completes. */
        public void waitFor(long semaphore, int destinationStage) {
            requireActive();
            if (semaphore == 0 || destinationStage == 0 || waitSemaphore != 0)
                throw new IllegalArgumentException("one non-null semaphore wait with a nonzero stage is required");
            waitSemaphore = semaphore; waitStage = destinationStage;
        }
        public void signal(long semaphore) {
            requireActive();
            if (semaphore == 0 || signalSemaphore != 0) throw new IllegalArgumentException("one non-null semaphore signal is required");
            signalSemaphore = semaphore;
        }
        /** For an unsuccessful acquire: no queue work, semaphore operation or retirement may be pending. */
        public void cancel() {
            requireActive();
            if (waitSemaphore != 0 || signalSemaphore != 0 || !retirements.isEmpty())
                throw new IllegalStateException("cancel must precede semaphore and resource registration");
            cancelled = true;
        }
        public void retireAfterFence(AutoCloseable resource) {
            requireActive(); retirements.add(Objects.requireNonNull(resource, "resource"));
        }
        private void requireActive() {
            requireOpen();
            if (!active || cancelled) throw new IllegalStateException("frame recording scope expired or cancelled");
        }
    }

    public final class Submission {
        private final int slot;
        private final long sequence;
        private Submission(int slot, long sequence) { this.slot = slot; this.sequence = sequence; }
        public long sequence() { return sequence; }
        public int slot() { return slot; }
        VulkanContext owningContext(){return context;}
        public void await() {
            context.requireOwner();
            if (closed) {
                if (sequence <= completed) return;
                throw new IllegalStateException("closed frame ring has incomplete work");
            }
            if (sequence > completed) awaitSlot(slots[slot]);
            else if (slots[slot].sequence == sequence) awaitSlot(slots[slot]);
        }
        public boolean isComplete() {
            context.requireOwner();
            if (closed) return sequence <= completed;
            poll();
            return sequence <= completed;
        }
        public void retireAfterFence(AutoCloseable resource) {
            context.requireOwner();
            if (closed && sequence > completed) throw new IllegalStateException("closed frame ring has incomplete work");
            Objects.requireNonNull(resource, "resource");
            if (sequence <= completed) {
                List<AutoCloseable> immediate = new ArrayList<>(); immediate.add(resource); retire(immediate);
            } else slots[slot].retirements.add(resource);
        }
    }

    private final class Slot implements AutoCloseable {
        private long pool;
        private long fence;
        private VkCommandBuffer command;
        private boolean pending;
        private long sequence;
        private List<AutoCloseable> retirements = new ArrayList<>();

        private Slot() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateCommandPool(context.device(), VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(context.queueFamily()).flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT), null, handle), "frame command pool");
                pool = handle.get(0);
                VkCommandBufferAllocateInfo allocate = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
                PointerBuffer pointer = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(context.device(), allocate, pointer), "frame command allocation");
                command = new VkCommandBuffer(pointer.get(0), context.device());
                check(vkCreateFence(context.device(), VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "frame fence");
                fence = handle.get(0);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        @Override public void close() {
            if (fence != 0) vkDestroyFence(context.device(), fence, null);
            if (pool != 0) vkDestroyCommandPool(context.device(), pool, null);
            pool = fence = 0;
        }
    }
}
