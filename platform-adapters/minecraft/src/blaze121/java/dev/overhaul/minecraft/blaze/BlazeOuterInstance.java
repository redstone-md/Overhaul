package dev.overhaul.minecraft.blaze;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Reads the enclosing instance of an inner class whose outer type is not on the compile classpath, as a mod's goals
 * often are. The field is looked up once per class, and a class without one reads as null.
 */
public final class BlazeOuterInstance {
    private static final ClassValue<MethodHandle> OUTER = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                Field outer = type.getDeclaredField("this$0");
                outer.setAccessible(true);
                return MethodHandles.lookup().unreflectGetter(outer).asType(MethodType.methodType(Object.class, Object.class));
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                return null;
            }
        }
    };

    private BlazeOuterInstance() {}

    /** The instance enclosing {@code inner}, or null when it has none that can be read. */
    public static Object of(Object inner) {
        MethodHandle getter = OUTER.get(inner.getClass());
        if (getter == null) return null;
        try {
            return (Object) getter.invokeExact(inner);
        } catch (Throwable unreadable) {
            return null;
        }
    }
}
