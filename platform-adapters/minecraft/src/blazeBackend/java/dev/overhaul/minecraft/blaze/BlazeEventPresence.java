package dev.overhaul.minecraft.blaze;

import java.lang.reflect.Method;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

/**
 * Whether anything listens to one event type on the game bus. A post nobody hears still builds its event, and some
 * are posted for every block a fluid looks at. The answer is read from the same listener list a post reads, so a
 * listener added later is heard at once. When the list cannot be found, every post counts as heard.
 */
public final class BlazeEventPresence {
    private final Class<? extends Event> type;
    private volatile ListenerList list;
    private volatile boolean unknown;

    public BlazeEventPresence(Class<? extends Event> type) {
        this.type = type;
    }

    public boolean heard() {
        ListenerList listeners = list;
        if (listeners == null) {
            if (unknown) return true;
            listeners = find();
            if (listeners == null) return true;
        }
        return listeners.getListeners().length != 0;
    }

    private ListenerList find() {
        try {
            Method lookup = NeoForge.EVENT_BUS.getClass().getDeclaredMethod("getListenerList", Class.class);
            lookup.setAccessible(true);
            ListenerList found = (ListenerList) lookup.invoke(NeoForge.EVENT_BUS, type);
            list = found;
            return found;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unknown = true;
            LoggerFactory.getLogger(BlazeEventPresence.class).info("Overhaul eventPresence off for {}: {}", type.getSimpleName(), failure.toString());
            return null;
        }
    }
}
