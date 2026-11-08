package dev.overhaul.minecraft.blaze;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

/**
 * A listener registered through {@code @SubscribeEvent} reads {@code receiveCanceled} off its annotation on every
 * cancellable event it gets, and an annotation is a proxy: each read goes through reflection and a map lookup. Mobs
 * post several cancellable events a tick to dozens of such listeners. Once mods have registered, each listener's
 * annotation is swapped for a plain object with the same values. The bus lives in a layer mixins cannot reach.
 */
public final class BlazeEventListeners {
    private BlazeEventListeners() {}

    public static void plainAnnotations() {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe unsafe = (Unsafe) theUnsafe.get(null);
            IEventBus bus = NeoForge.EVENT_BUS;
            Class<?> wrapper = Class.forName("net.neoforged.bus.SubscribeEventListener", false, bus.getClass().getClassLoader());
            long info = unsafe.objectFieldOffset(wrapper.getDeclaredField("subInfo"));
            long registered = unsafe.objectFieldOffset(bus.getClass().getDeclaredField("listeners"));
            int swapped = 0;
            for (Object listeners : ((Map<?, ?>) unsafe.getObject(bus, registered)).values()) {
                Object[] copy;
                synchronized (listeners) {
                    copy = ((List<?>) listeners).toArray();
                }
                for (Object listener : copy) {
                    if (!wrapper.isInstance(listener) || !(unsafe.getObject(listener, info) instanceof SubscribeEvent annotation)
                            || annotation instanceof Plain) continue;
                    unsafe.putObject(listener, info, new Plain(annotation.priority(), annotation.receiveCanceled()));
                    swapped++;
                }
            }
            LoggerFactory.getLogger(BlazeEventListeners.class).info("Overhaul eventListeners: {} annotations made plain", swapped);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LoggerFactory.getLogger(BlazeEventListeners.class).info("Overhaul eventListeners off: {}", failure.toString());
        }
    }

    @SuppressWarnings("ClassExplicitlyAnnotation")
    private static final class Plain implements SubscribeEvent {
        private final EventPriority priority;
        private final boolean receiveCanceled;

        Plain(EventPriority priority, boolean receiveCanceled) {
            this.priority = priority;
            this.receiveCanceled = receiveCanceled;
        }

        @Override public EventPriority priority() { return priority; }
        @Override public boolean receiveCanceled() { return receiveCanceled; }
        @Override public Class<? extends Annotation> annotationType() { return SubscribeEvent.class; }
    }
}