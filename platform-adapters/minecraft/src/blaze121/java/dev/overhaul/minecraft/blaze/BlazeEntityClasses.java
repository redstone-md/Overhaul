package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.jetbrains.annotations.Nullable;

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
    /** Stands for every entity that a collision query could answer with, itself or through its parts. */
    private static final Object COLLIDERS = new Object();
    /**
     * A class that answers a collision query: one that overrides {@code canBeCollidedWith}, carries parts the query
     * also tests, or is the dragon, whose parts vanilla tests. A mod changing {@code Entity.canBeCollidedWith} itself
     * is not seen, which is why the switch exists.
     */
    private static final ClassValue<Boolean> COLLIDER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return EnderDragon.class.isAssignableFrom(type) || overrides(type, "canBeCollidedWith") || overrides(type, "isMultipartEntity");
        }
    };
    /** A mover that chooses its own colliders, as a boat takes in pushable mobs. */
    private static final ClassValue<Boolean> OWN_COLLISIONS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return overrides(type, "canCollideWith", Entity.class);
        }
    };

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
        State state = state(scanned, SERVER_SCANS);
        return state != null && state.absent(scanned, entities, type);
    }

    /** Whether {@code mover}, or a query without one, would find no entity to collide with anywhere in the level. */
    public static boolean noColliders(Level scanned, LevelEntityGetter<Entity> entities, @Nullable Entity mover) {
        if (mover != null && OWN_COLLISIONS.get(mover.getClass())) return false;
        State state = state(scanned, true);
        return state != null && state.absent(scanned, entities, COLLIDERS);
    }

    private static @Nullable State state(Level scanned, boolean server) {
        if (scanned.isClientSide()) return RenderSystem.isOnRenderThread() ? CLIENT : null;
        if (!server || !(scanned instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
        synchronized (SERVER) { return SERVER.computeIfAbsent(scanned, key -> new State()); }
    }

    private static boolean overrides(Class<?> type, String name, Class<?>... parameters) {
        try {
            Class<?> owner = type.getMethod(name, parameters).getDeclaringClass();
            return owner != Entity.class && Entity.class.isAssignableFrom(owner);
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    private static boolean matches(Object kind, Entity entity) {
        return kind == COLLIDERS ? COLLIDER.get(entity.getClass()) : ((Class<?>) kind).isInstance(entity);
    }

    private static final class State {
        private final Map<Object, Boolean> absent = new IdentityHashMap<>();
        private volatile boolean stale;
        private Level level;
        private long since;

        void forget() {
            stale = true;
        }

        void added(Entity entity, boolean owner) {
            if (!owner) { stale = true; return; }
            for (var entry : absent.entrySet()) if (entry.getValue() && matches(entry.getKey(), entity)) entry.setValue(false);
        }

        boolean absent(Level scanned, LevelEntityGetter<Entity> entities, Object kind) {
            long now = scanned.getGameTime();
            if (stale || scanned != level || now < since || now - since >= FORGET_TICKS) {
                stale = false;
                absent.clear();
                level = scanned;
                since = now;
            }
            Boolean known = absent.get(kind);
            if (known != null) return known;
            boolean none = true;
            for (Entity entity : entities.getAll()) {
                if (matches(kind, entity)) {
                    none = false;
                    break;
                }
            }
            absent.put(kind, none);
            return none;
        }
    }
}
