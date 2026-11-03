package dev.overhaul.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.overhaul.minecraft.backend.OverhaulGpuDevice;
import dev.overhaul.minecraft.smoke.WorldSmokeProbe;

/** This source variant keeps the disposable world alive until its actual game frame is captured. */
public final class ModelSmokeFactory {
    private ModelSmokeFactory() {}
    public static WorldSmokeProbe create() {
        return (minecraft,section)-> {
            if (!(RenderSystem.getDevice() instanceof OverhaulGpuDevice device)) {
                throw new IllegalStateException("World backend smoke requires the actual Overhaul device");
            }
            return device.awaitWorldCapture();
        };
    }
}
