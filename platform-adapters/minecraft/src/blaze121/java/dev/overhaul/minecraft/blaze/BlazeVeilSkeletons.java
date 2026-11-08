package dev.overhaul.minecraft.blaze;

import dev.overhaul.minecraft.blaze.mixin.LevelEntities121Access;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * The entity list {@link VeilSkeletonPatch} hands Veil's skeleton animator tick. The tick walks every client entity
 * for skeleton parents and ticks their animators; with none in the level it would find nothing, so it gets an empty list.
 */
public final class BlazeVeilSkeletons {
    private static final Class<?> PARENT = parent();

    private BlazeVeilSkeletons() {}

    public static Iterable<Entity> candidates(ClientLevel level) {
        if (PARENT != null && BlazeEntityClasses.absent(level, ((LevelEntities121Access) level).overhaul$entities(), PARENT)) return List.of();
        return level.entitiesForRendering();
    }

    private static Class<?> parent() {
        try {
            return Class.forName("foundry.veil.api.client.necromancer.SkeletonParent", false, BlazeVeilSkeletons.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError missing) {
            return null;
        }
    }
}
