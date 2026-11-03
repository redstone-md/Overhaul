package dev.overhaul.minecraft.legacy;

import net.minecraftforge.fml.common.Mod;

@Mod(modid="overhaul",name="overhaul",version="0.1.0-SNAPSHOT",acceptableRemoteVersions="*")
public final class OverhaulLegacy {
    public OverhaulLegacy() { dev.overhaul.adapter.AdapterBootstrap.initialize("Forge1.12.2"); }
}
