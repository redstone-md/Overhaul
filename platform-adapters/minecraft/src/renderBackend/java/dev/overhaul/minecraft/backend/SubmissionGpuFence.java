package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.commands.GpuFence;
import dev.overhaul.vulkan.FrameRing;

/** A game fence references actual prior queue completion; ring slots own native handles. */
final class SubmissionGpuFence implements GpuFence {
    private final OverhaulDeviceBackend owner;
    private final FrameRing.Submission submission;
    private boolean closed;
    SubmissionGpuFence(OverhaulDeviceBackend owner,FrameRing.Submission submission) { this.owner=owner; this.submission=submission; }
    @Override public boolean awaitCompletion(long timeoutNanos) {
        owner.requireOwner();
        if (closed) throw new IllegalStateException("Fence is closed");
        return owner.queue().await(submission,timeoutNanos);
    }
    @Override public void close() { owner.requireOwner(); closed=true; }
}
