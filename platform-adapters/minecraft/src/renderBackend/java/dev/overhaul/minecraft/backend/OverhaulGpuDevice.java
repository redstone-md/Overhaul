package dev.overhaul.minecraft.backend;

import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.util.concurrent.CompletableFuture;

/** Shared game frontend with a narrow, instance-owned development evidence interface. */
public final class OverhaulGpuDevice extends FrontendGpuDevice {
    private final OverhaulDeviceBackend backend;
    OverhaulGpuDevice(OverhaulDeviceBackend backend) { super(backend); this.backend=backend; }
    public CompletableFuture<String> awaitWorldCapture() { return backend.awaitWorldCapture(); }
    public CompletableFuture<String> awaitWorldCapture(String label) { return backend.awaitWorldCapture(label); }
}
