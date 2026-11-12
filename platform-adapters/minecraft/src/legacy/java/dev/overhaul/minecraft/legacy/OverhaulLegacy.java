package dev.overhaul.minecraft.legacy;

import net.minecraftforge.fml.common.Mod;

@Mod(modid="overhaul",name="Overhaul",version="0.0.1",acceptableRemoteVersions="*")
public final class OverhaulLegacy {
    public OverhaulLegacy() { dev.overhaul.adapter.AdapterBootstrap.initialize("Forge1.12.2"); }
}
