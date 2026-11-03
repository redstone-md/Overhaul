package dev.overhaul.minecraft;

import dev.overhaul.minecraft.smoke.NativeModelProbe;
import dev.overhaul.minecraft.smoke.WorldSmokeProbe;

/** Selected only by the isolated -PworldSmoke -PmodelSmoke development build. */
public final class ModelSmokeFactory {
    private ModelSmokeFactory() { }
    public static WorldSmokeProbe create() { return new NativeModelProbe(); }
}
