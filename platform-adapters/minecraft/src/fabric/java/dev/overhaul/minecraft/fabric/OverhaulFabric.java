package dev.overhaul.minecraft.fabric;

import net.fabricmc.api.ModInitializer;

/** Entrypoint intentionally has no client/native references, so dedicated servers stay safe. */
public final class OverhaulFabric implements ModInitializer {
    @Override public void onInitialize() { dev.overhaul.adapter.AdapterBootstrap.initialize("Fabric"); }
}
