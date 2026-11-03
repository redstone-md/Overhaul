package dev.overhaul.minecraft;

import dev.overhaul.minecraft.smoke.WorldSmokeProbe;

/** World-only smoke does not load any native renderer classes. */
public final class ModelSmokeFactory {
    private ModelSmokeFactory() { }
    public static WorldSmokeProbe create() { return null; }
}
