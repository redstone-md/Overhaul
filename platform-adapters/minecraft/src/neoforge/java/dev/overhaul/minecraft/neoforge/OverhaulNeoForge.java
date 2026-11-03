package dev.overhaul.minecraft.neoforge;

import net.neoforged.fml.common.Mod;

/** Server-safe registration; no Vulkan or client classes are linked on the server. */
@Mod("overhaul")
public final class OverhaulNeoForge {
    public OverhaulNeoForge() { dev.overhaul.adapter.AdapterBootstrap.initialize("NeoForge"); }
}
