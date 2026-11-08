package dev.overhaul.minecraft.blaze;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.world.entity.Entity;

/**
 * The guard {@link SableCullPatch} puts in front of Sable's entity cull hook. The hook only answers for an entity
 * inside a loaded sub-level's plot or tracking one; in a level with none loaded and no tracked sub-level it ends with
 * no answer, which is what skipping it does. Entities that skip culling still go through the hook, which answers them.
 */
public final class BlazeSableCull {
    private static final MethodHandle TRACKING = tracking();

    private BlazeSableCull() {}

    public static boolean skip(Entity entity) {
        if (entity.noCulling || TRACKING == null || !BlazeSableLevels.empty(entity.level())) return false;
        try {
            return (Object) TRACKING.invokeExact(entity) == null;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static MethodHandle tracking() {
        try {
            Class<?> extension = Class.forName("dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension",
                    false, BlazeSableCull.class.getClassLoader());
            Class<?> subLevel = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel", false, BlazeSableCull.class.getClassLoader());
            return MethodHandles.publicLookup().findVirtual(extension, "sable$getTrackingSubLevel", MethodType.methodType(subLevel))
                    .asType(MethodType.methodType(Object.class, Entity.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return null;
        }
    }
}
