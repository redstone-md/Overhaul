package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.api.commands.GpuFence;

/** All commands preceding construction have already passed ImmediateCommands' native fence wait. */
final class CompletedGpuFence implements GpuFence {
    private final OverhaulDeviceBackend owner;
    private boolean closed;

    CompletedGpuFence(OverhaulDeviceBackend owner) { this.owner=owner; }

    @Override public boolean awaitCompletion(long timeoutNanos) {
        owner.context().device();
        if (closed) throw new IllegalStateException("fence is closed");
        if (timeoutNanos<0 && timeoutNanos!=NO_TIMEOUT) throw new IllegalArgumentException("invalid timeout");
        return true;
    }

    @Override public void close() { closed=true; }
}
