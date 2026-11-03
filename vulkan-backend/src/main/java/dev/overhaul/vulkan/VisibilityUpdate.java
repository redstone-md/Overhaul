package dev.overhaul.vulkan;

/** Queue receipt and transfer volume for a visibility change; unchanged masks enqueue no copy. */
public record VisibilityUpdate(int visibleDraws, int changedDraws, int uploadedBytes, FrameRing.Submission completion) {
    public void await() { completion.await(); }
}
