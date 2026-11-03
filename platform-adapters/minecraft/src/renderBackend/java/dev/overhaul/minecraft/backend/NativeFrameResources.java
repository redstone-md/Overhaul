package dev.overhaul.minecraft.backend;

import dev.overhaul.vulkan.GpuAllocator;
import dev.overhaul.vulkan.ImmediateCommands;

/** Owner-thread native frame access for integrated render graph execution. */
public interface NativeFrameResources extends NativePassResources {
    GpuAllocator allocator();
    /** Submits queued frontend work before recording a native frame dependency. */
    ImmediateCommands commands();
}
