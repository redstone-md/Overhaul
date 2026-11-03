package dev.overhaul.minecraft.forge;

import net.minecraftforge.fml.common.Mod;

/** Server-safe registration; client state is owned by the Minecraft instance mixin. */
@Mod("overhaul")
public final class OverhaulForge {
    public OverhaulForge() { dev.overhaul.adapter.AdapterBootstrap.initialize("Forge"); }
}
