package dev.overhaul.minecraft.blaze;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** What the loader adds to vanilla: custom outlines. */
public final class BlazeLoader120 {
    private BlazeLoader120() {
    }

    public static boolean customOutline(Entity entity, Player viewer) {
        return entity.hasCustomOutlineRendering(viewer);
    }
}
