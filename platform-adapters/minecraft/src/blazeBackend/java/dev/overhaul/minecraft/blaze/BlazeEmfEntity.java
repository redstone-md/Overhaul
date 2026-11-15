package dev.overhaul.minecraft.blaze;

import net.minecraft.client.Minecraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The entity Entity Model Features is animating, looked up without linking against the mod. Render thread only. */
final class BlazeEmfEntity {
    private static MethodHandle animatedEntity;
    private static boolean unresolved;

    private BlazeEmfEntity() {
    }

    /** The entity being animated, or null when there is none or the mod is missing. */
    static Object current() {
        MethodHandle handle = handle();
        if (handle == null) return null;
        try {
            return handle.invoke();
        } catch (Throwable failure) {
            unresolved = true;
            animatedEntity = null;
            return null;
        }
    }

    /** Whether the entity being animated is the local player. */
    static boolean localPlayer() {
        Object entity = current();
        return entity != null && entity == Minecraft.getInstance().player;
    }

    private static MethodHandle handle() {
        if (animatedEntity != null || unresolved) return animatedEntity;
        try {
            Class<?> context = Class.forName("traben.entity_model_features.models.animation.EMFAnimationEntityContext",
                    false, BlazeEmfEntity.class.getClassLoader());
            Class<?> entity = Class.forName("traben.entity_model_features.utils.EMFEntity", false, context.getClassLoader());
            animatedEntity = MethodHandles.publicLookup().findStatic(context, "getEMFEntity", MethodType.methodType(entity))
                    .asType(MethodType.methodType(Object.class));
        } catch (ReflectiveOperationException | LinkageError failure) {
            unresolved = true;
        }
        return animatedEntity;
    }
}
