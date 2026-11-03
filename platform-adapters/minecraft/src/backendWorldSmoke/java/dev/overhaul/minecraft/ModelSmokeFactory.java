package dev.overhaul.minecraft;

import dev.overhaul.minecraft.smoke.WorldSmokeProbe;

/** This source variant keeps the disposable world alive until its actual game frame is captured. */
public final class ModelSmokeFactory {
    private ModelSmokeFactory() {}
    public static WorldSmokeProbe create() {
        return new dev.overhaul.minecraft.smoke.TerrainWorldProbe();
    }
}
