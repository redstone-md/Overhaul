package dev.overhaul.vulkan;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * One pack graph waiting for its place in a frame's command buffer. Its layout proposals commit once the buffer is
 * accepted by the queue, and roll back if the recording is dropped first. Render-thread only.
 */
public final class PackRecording implements AutoCloseable {
    /** Takes ownership of the recording and records it in queue order. */
    @FunctionalInterface public interface Recorder { void add(PackRecording recording); }

    private final VulkanRenderGraph graph;
    private final List<GpuImage.LayoutChange> changes = new ArrayList<>();
    private boolean closed;

    PackRecording(VulkanRenderGraph graph) {
        this.graph = graph;
    }

    public void record(VkCommandBuffer command) {
        graph.record(command, changes);
    }

    public void accepted() {
        for (GpuImage.LayoutChange change : changes) change.commit();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            for (int index = changes.size() - 1; index >= 0; index--) changes.get(index).close();
        } finally {
            changes.clear();
            graph.close();
        }
    }
}
