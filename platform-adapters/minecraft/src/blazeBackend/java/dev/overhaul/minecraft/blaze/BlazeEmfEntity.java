package dev.overhaul.minecraft.blaze;

import net.minecraft.client.Minecraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The entity Entity Model Features is animating and its frame clock, looked up without linking against the mod. Render thread only. */
final class BlazeEmfEntity {
    private static MethodHandle animatedEntity, frameCounter, frameTime;
    private static boolean unresolved;

    private BlazeEmfEntity() {
    }

    /** The entity being animated, or null when there is none or the mod is missing. */
    static Object current() {
        if (!resolved()) return null;
        try {
            return animatedEntity.invoke();
        } catch (Throwable failure) {
            unresolved = true;
            return null;
        }
    }

    /** Whether the entity being animated is the local player. */
    static boolean localPlayer() {
        Object entity = current();
        return entity != null && entity == Minecraft.getInstance().player;
    }

    /**
     * Owes the hand animation the time of the frame now being drawn, which no animation of the mod sees; does nothing
     * when the mod is missing.
     */
    static void oweFrame() {
        if (!resolved()) return;
        try {
            BlazeHandAnimation.owe((float) frameCounter.invokeExact(), (float) frameTime.invokeExact());
        } catch (Throwable failure) {
            unresolved = true;
        }
    }

    private static boolean resolved() {
        if (animatedEntity != null || unresolved) return !unresolved;
        try {
            Class<?> context = Class.forName("traben.entity_model_features.models.animation.EMFAnimationEntityContext",
                    false, BlazeEmfEntity.class.getClassLoader());
            Class<?> entity = Class.forName("traben.entity_model_features.utils.EMFEntity", false, context.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            frameCounter = lookup.findStatic(context, "getFrameCounter", MethodType.methodType(float.class));
            frameTime = lookup.findStatic(context, "getFrameTime", MethodType.methodType(float.class));
            animatedEntity = lookup.findStatic(context, "getEMFEntity", MethodType.methodType(entity)).asType(MethodType.methodType(Object.class));
        } catch (ReflectiveOperationException | LinkageError failure) {
            unresolved = true;
        }
        return !unresolved;
    }
}
