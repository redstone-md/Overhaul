package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.commands.GpuFence;
import dev.overhaul.vulkan.FrameRing;
import org.lwjgl.vulkan.VkCommandBuffer;

/** A logical fence captures a submission boundary without forcing an early GPU submit. */
final class SubmissionGpuFence implements GpuFence {
    private final OverhaulDeviceBackend owner;
    private final FrameRing.Submission submission;
    private final Marker marker;
    private boolean closed;
    private SubmissionGpuFence(OverhaulDeviceBackend owner,FrameRing.Submission submission,Marker marker) {
        this.owner=owner; this.submission=submission; this.marker=marker;
    }
    static SubmissionGpuFence capture(OverhaulDeviceBackend owner) {
        if (owner.queue().pendingOperations()==0) return new SubmissionGpuFence(owner,owner.queue().latest(),null);
        var marker=new Marker(); owner.queue().enqueue(marker);
        return new SubmissionGpuFence(owner,null,marker);
    }
    @Override public boolean awaitCompletion(long timeoutNanos) {
        owner.requireOwner();
        if (closed) throw new IllegalStateException("Fence is closed");
        if (timeoutNanos<0&&timeoutNanos!=NO_TIMEOUT) throw new IllegalArgumentException("Invalid fence timeout");
        if (marker==null) return owner.queue().await(submission,timeoutNanos);
        if (marker.failed) throw new IllegalStateException("Fence commands were rejected before submission");
        if (marker.submission==null) {
            if (timeoutNanos==0) {
                owner.queue().poll();
                if (marker.submission==null) return false;
            } else owner.queue().flush();
        }
        if (marker.failed) throw new IllegalStateException("Fence commands were rejected before submission");
        return owner.queue().await(marker.submission,timeoutNanos);
    }
    @Override public void close() { owner.requireOwner(); closed=true; }
    private static final class Marker implements OverhaulGpuOperation {
        private FrameRing.Submission submission;
        private boolean failed;
        @Override public void record(VkCommandBuffer command) {}
        @Override public void submitted(FrameRing.Submission submission) { this.submission=submission; }
        @Override public void close() { if (submission==null) failed=true; }
    }
}
