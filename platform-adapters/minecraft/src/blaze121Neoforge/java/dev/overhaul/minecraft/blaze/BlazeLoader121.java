package dev.overhaul.minecraft.blaze;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;

/** What the loader adds to vanilla: biome modifiers and custom outlines. */
public final class BlazeLoader121 {
    private BlazeLoader121() {
    }

    public static boolean customOutline(Entity entity, Player viewer) {
        return entity.hasCustomOutlineRendering(viewer);
    }

    /** Downfall after the loader's biome modifiers. */
    public static float downfall(Biome biome) {
        return biome.getModifiedClimateSettings().downfall();
    }
}
