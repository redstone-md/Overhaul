package dev.overhaul.minecraft.blaze.mixin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Architectury events fan out through a proxy that, for every listener on every post, looked up a fresh method handle,
 * bound it to the listener and called it with boxed arguments, so each living tick and server tick rebuilt handles.
 * A reflective call keeps its accessor on the method itself and reaches the same listener with the same arguments;
 * whatever the listener throws is still what the caller sees.
 */
@Pseudo
@Mixin(targets = "dev.architectury.event.EventFactory", remap = false)
abstract class ArchitecturyEventInvoke121Mixin {
    /**
     * @author Overhaul
     * @reason call listeners without building a method handle each time
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    private static <T, R> R invokeMethod(T listener, Method method, Object[] args) throws Throwable {
        try {
            return (R) method.invoke(listener, args);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause();
        }
    }
}