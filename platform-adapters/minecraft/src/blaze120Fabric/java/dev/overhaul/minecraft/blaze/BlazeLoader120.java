package dev.overhaul.minecraft.blaze;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Fabric adds no custom outlines to vanilla. */
public final class BlazeLoader120 {
    private BlazeLoader120() {
    }

    public static boolean customOutline(Entity entity, Player viewer) {
        return false;
    }
}
