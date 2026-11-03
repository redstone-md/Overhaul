package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.util.concurrent.CompletableFuture;

/** Shared game frontend with owner-scoped native frame access. */
public final class OverhaulGpuDevice extends FrontendGpuDevice {
    private final OverhaulDeviceBackend backend;
    OverhaulGpuDevice(OverhaulDeviceBackend backend) { super(backend); this.backend=backend; }
    public NativeFrameResources nativeFrames(){backend.requireOpen();return backend;}
    public CompletableFuture<String> awaitWorldCapture() { return backend.awaitWorldCapture(); }
    public CompletableFuture<String> awaitWorldCapture(String label) { return backend.awaitWorldCapture(label); }
}
