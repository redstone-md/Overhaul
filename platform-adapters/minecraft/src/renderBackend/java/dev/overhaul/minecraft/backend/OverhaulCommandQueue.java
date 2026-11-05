package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.VulkanStack;

import dev.overhaul.vulkan.FrameRing;
import org.lwjgl.system.MemoryStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.vulkan.VK10.*;

/** One device queue order, bounded command batches, and range-aware host synchronization. */
final class OverhaulCommandQueue implements AutoCloseable {
    private static final int MAX_OPERATIONS=256;
    private static final long MAX_STAGING_BYTES=64L*1024*1024;
    private final OverhaulDeviceBackend owner;
    private final FrameRing frames;
    private final long mapAlignment;
    private final List<OverhaulGpuOperation> pending=new ArrayList<>();
    private final ArrayDeque<Batch> inFlight=new ArrayDeque<>();
    private final ArrayDeque<Runnable> callbacks=new ArrayDeque<>();
    private FrameRing.Submission latest;
    private long stagingBytes,submissions,operations,hostWaits;
    private boolean flushing,dispatching,closed;

    OverhaulCommandQueue(OverhaulDeviceBackend owner) {
        this.owner=owner; frames=new FrameRing(owner.context(),3);
        try (MemoryStack stack=VulkanStack.push()) {
            var properties=org.lwjgl.vulkan.VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(owner.context().physicalDevice(),properties);
            mapAlignment=properties.limits().nonCoherentAtomSize();
        }
    }
    void enqueue(OverhaulGpuOperation operation) {
        boolean accepted=false;
        try {
            requireOpen();
            if (operation.stagingBytes()<0) throw new IllegalArgumentException("Negative staging byte count");
            if (!pending.isEmpty()&&(pending.size()>=MAX_OPERATIONS
                    ||operation.stagingBytes()>MAX_STAGING_BYTES-stagingBytes)) flush();
            long nextBytes=Math.addExact(stagingBytes,operation.stagingBytes());
            pending.add(operation); stagingBytes=nextBytes; accepted=true;
            if (stagingBytes>=MAX_STAGING_BYTES) flush();
        } catch (RuntimeException|Error failure) {
            if (!accepted) try { operation.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    FrameRing.Submission flush() {
        requireOpen();
        if (flushing) throw new IllegalStateException("Nested command batch recording");
        if (pending.isEmpty()) return latest;
        Batch batch=new Batch(new ArrayList<>(pending)); pending.clear(); stagingBytes=0;
        flushing=true;
        try {
            FrameRing.Submission submitted=frames.submit(frame-> {
                try (MemoryStack stack=VulkanStack.push()) {
                    // Includes write-after-read dependencies between separate queue batches.
                    var barrier=org.lwjgl.vulkan.VkMemoryBarrier.calloc(1,stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT);
                    vkCmdPipelineBarrier(frame.command(),VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,barrier,null,null);
                }
                for (var operation:batch.work) operation.record(frame.command());
                frame.retireAfterFence(batch);
            });
            batch.submission=submitted;
            inFlight.addLast(batch); latest=submitted; submissions++; operations+=batch.work.size();
            for (var operation:batch.work) operation.submitted(submitted);
            return submitted;
        } catch (RuntimeException|Error failure) {
            // Accepted work cannot roll back its image state or destroy live resources.
            if (batch.submission!=null) {
                try { batch.submission.await(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            try { batch.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally { flushing=false; }
    }
    void poll() { requireOpen(); frames.poll(); dispatchCallbacks(); }
    void awaitBuffer(OverhaulBuffer buffer,long offset,long length) {
        requireOpen();
        if (length==0) return;
        long start=offset-offset%mapAlignment;
        long end=Math.min(buffer.size(),Math.addExact(offset+length,mapAlignment-1)/mapAlignment*mapAlignment);
        poll();
        if (pending.stream().anyMatch(operation->operation.usesBuffer(buffer,start,end-start))) flush();
        FrameRing.Submission needed=null;
        for (Batch batch:inFlight) {
            if (batch.work.stream().anyMatch(operation->operation.usesBuffer(buffer,start,end-start))) needed=batch.submission;
        }
        await(needed,-1L);
    }
    boolean resourceComplete(BackendResource resource) {
        requireOpen(); poll();
        if (pending.stream().anyMatch(operation->operation.usesResource(resource))) flush();
        FrameRing.Submission needed=null;
        for (Batch batch:inFlight) {
            if (batch.work.stream().anyMatch(operation->operation.usesResource(resource))) needed=batch.submission;
        }
        return await(needed,0);
    }
    boolean await(FrameRing.Submission submission,long timeoutNanos) {
        requireOpen();
        if (timeoutNanos<0&&timeoutNanos!=-1L) throw new IllegalArgumentException("Invalid fence timeout");
        boolean result=true;
        if (submission!=null) {
            if (timeoutNanos!=0&&!submission.isComplete()) hostWaits++;
            result=submission.await(timeoutNanos);
        }
        // An older receipt may be covered by a newer completed sequence while
        // its own slot's resources still need retirement.
        frames.poll(); dispatchCallbacks(); return result;
    }
    void afterCompletion(Runnable callback) { owner.requireOwner(); callbacks.addLast(callback); }
    void submittedExternal(FrameRing.Submission submission) {
        requireOpen();
        if (!pending.isEmpty()) throw new IllegalStateException("Flush rendering before external queue submission");
        latest=submission;
    }
    private void dispatchCallbacks() {
        if (dispatching||flushing) return;
        dispatching=true;
        try { while (!callbacks.isEmpty()) callbacks.removeFirst().run(); }
        finally { dispatching=false; }
    }
    void drain() {
        requireOpen();
        do { flush(); frames.drain(); await(latest,-1L); dispatchCallbacks(); } while (!pending.isEmpty());
    }
    long submissions() { return submissions; }
    long operations() { return operations; }
    long hostWaits() { return hostWaits; }
    long blockingWaitCalls() { return frames.blockingWaitCalls(); }
    long mappingAlignment() { return mapAlignment; }
    int pendingOperations() { return pending.size(); }
    int inFlightBatches() { return inFlight.size(); }
    FrameRing.Submission latest() { return latest; }
    private void requireOpen() { owner.requireOwner(); if (closed) throw new IllegalStateException("Command queue is closed"); }
    @Override public void close() {
        if (closed) return;
        drain(); frames.close(); closed=true;
    }
    private final class Batch implements AutoCloseable {
        private final List<OverhaulGpuOperation> work;
        private FrameRing.Submission submission;
        private boolean retired;
        Batch(List<OverhaulGpuOperation> work) { this.work=work; }
        @Override public void close() {
            if (retired) return;
            retired=true; inFlight.remove(this);
            RuntimeException failure=null;
            // Unsubmitted rollback reverses transactions; successful completion preserves callback order.
            for (int item=0;item<work.size();item++) {
                int index=submission==null?work.size()-1-item:item;
                try { work.get(index).close(); }
                catch (RuntimeException error) { if (failure==null) failure=error; else failure.addSuppressed(error); }
            }
            if (failure!=null) throw failure;
        }
    }
}
