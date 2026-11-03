package dev.overhaul.minecraft;

import dev.overhaul.minecraft.smoke.EntityQueryWorldProbe;
import dev.overhaul.minecraft.smoke.WorldSmokeProbe;

/** Query integration checks run on the ordinary game backend, without renderer dependencies. */
public final class ModelSmokeFactory {
    private ModelSmokeFactory(){}
    public static WorldSmokeProbe create(){return new EntityQueryWorldProbe();}
}
