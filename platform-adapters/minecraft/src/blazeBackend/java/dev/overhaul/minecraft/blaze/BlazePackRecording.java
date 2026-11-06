package dev.overhaul.minecraft.blaze;

import dev.overhaul.vulkan.FrameRing;
import dev.overhaul.vulkan.PackRecording;
import org.lwjgl.vulkan.VkCommandBuffer;

/** A pack graph in the frame queue, between the draws queued before it and after it. */
final class BlazePackRecording implements BlazeQueue.Operation {
    private final PackRecording recording;

    BlazePackRecording(PackRecording recording) {
        this.recording = recording;
    }

    @Override public void record(VkCommandBuffer command) {
        recording.record(command);
    }

    @Override public void submitted(FrameRing.Submission submission) {
        recording.accepted();
    }

    @Override public void close() {
        recording.close();
    }
}
