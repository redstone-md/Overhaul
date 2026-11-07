package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;

/**
 * Knows which entity classes a level holds no instance of, so a scan for one need not walk the sections around it.
 * The client level answers on the render thread and each server level on the server thread; other threads are told to
 * walk. An added entity marks its classes present at once; removals only leave a class looking present for up to a
 * second, which costs the walk and nothing else.
 */
public final class BlazeEntityClasses {
    private static final long FORGET_TICKS = 20;
    private static final State CLIENT = new State();
    private static final Map<Level, State> SERVER = new WeakHashMap<>();
    private static final boolean SERVER_SCANS = BlazeOptimization.SERVER_CLASS_SCANS.on();

    private BlazeEntityClasses() {}

    /** A client entity joined. */
    public static void added(Entity entity) {
        CLIENT.added(entity, RenderSystem.isOnRenderThread());
    }

    /** A server entity joined {@code level}. */
    public static void added(ServerLevel level, Entity entity) {
        if (!level.getServer().isSameThread()) {
            synchronized (SERVER) { SERVER.values().forEach(State::forget); }
            return;
        }
        State state = SERVER.get(level);
        if (state != null) state.added(entity, true);
    }

    public static boolean absent(Level scanned, LevelEntityGetter<Entity> entities, Class<?> type) {
        if (type == Entity.class) return false;
        State state;
        if (scanned.isClientSide()) {
            if (!RenderSystem.isOnRenderThread()) return false;
            state = CLIENT;
        } else {
            if (!SERVER_SCANS || !(scanned instanceof ServerLevel server) || !server.getServer().isSameThread()) return false;
            synchronized (SERVER) { state = SERVER.computeIfAbsent(scanned, key -> new State()); }
        }
        return state.absent(scanned, entities, type);
    }

    private static final class State {
        private final Map<Class<?>, Boolean> absent = new IdentityHashMap<>();
        private volatile boolean stale;
        private Level level;
        private long since;

        void forget() {
            stale = true;
        }

        void added(Entity entity, boolean owner) {
            if (!owner) { stale = true; return; }
            for (var entry : absent.entrySet()) if (entry.getValue() && entry.getKey().isInstance(entity)) entry.setValue(false);
        }

        boolean absent(Level scanned, LevelEntityGetter<Entity> entities, Class<?> type) {
            long now = scanned.getGameTime();
            if (stale || scanned != level || now < since || now - since >= FORGET_TICKS) {
                stale = false;
                absent.clear();
                level = scanned;
                since = now;
            }
            Boolean known = absent.get(type);
            if (known != null) return known;
            boolean none = true;
            for (Entity entity : entities.getAll()) {
                if (type.isInstance(entity)) {
                    none = false;
                    break;
                }
            }
            absent.put(type, none);
            return none;
        }
    }
}
