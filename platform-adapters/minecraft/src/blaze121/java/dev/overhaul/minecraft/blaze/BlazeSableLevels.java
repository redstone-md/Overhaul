package dev.overhaul.minecraft.blaze;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.world.level.Level;
import org.slf4j.LoggerFactory;

/**
 * Whether a level has any Sable sub-level loaded. Sable's per-block and per-particle hooks only ever change anything
 * near a loaded sub-level, so a level with none behaves as vanilla. Sable is reached by reflection: the build does not
 * depend on it.
 */
public final class BlazeSableLevels {
    private static final MethodHandle CONTAINER, LOADED;

    static {
        MethodHandle container = null, loaded = null;
        try {
            Class<?> holder = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer", false, BlazeSableLevels.class.getClassLoader());
            container = MethodHandles.publicLookup().findStatic(holder, "getContainer", MethodType.methodType(holder, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));
            loaded = MethodHandles.publicLookup().findVirtual(holder, "getLoadedCount", MethodType.methodType(int.class))
                    .asType(MethodType.methodType(int.class, Object.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LoggerFactory.getLogger(BlazeSableLevels.class).info("Overhaul Sable level check off: {}", failure.toString());
            container = null;
            loaded = null;
        }
        CONTAINER = container;
        LOADED = loaded;
    }

    private BlazeSableLevels() {}

    /** Whether the handles to Sable's container were found. */
    public static boolean ready() {
        return CONTAINER != null;
    }

    /** Whether Sable has a container for the level and no sub-level loaded in it. */
    public static boolean empty(Level level) {
        if (CONTAINER == null) return false;
        try {
            Object container = (Object) CONTAINER.invokeExact(level);
            return container != null && (int) LOADED.invokeExact(container) == 0;
        } catch (Throwable failure) {
            return false;
        }
    }
}
