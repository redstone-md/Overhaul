package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;

/**
 * Knows which entity classes the client holds no instance of, so a scan for one need not walk the sections around it.
 * Forgets on every addition; removals only leave a class looking present for up to a second, which costs the walk and nothing else.
 */
public final class BlazeEntityClasses {
    private static final Map<Class<?>, Boolean> ABSENT = new IdentityHashMap<>();
    private static final long FORGET_TICKS = 20;
    private static volatile boolean added;
    private static Level level;
    private static long since;

    private BlazeEntityClasses() {}

    public static void added() {
        added = true;
    }

    /** Only the render thread answers; every other caller is told to walk. */
    public static boolean absent(Level scanned, LevelEntityGetter<Entity> entities, Class<?> type) {
        if (type == Entity.class || !scanned.isClientSide() || !RenderSystem.isOnRenderThread()) return false;
        long now = scanned.getGameTime();
        if (added || scanned != level || now < since || now - since >= FORGET_TICKS) {
            added = false;
            ABSENT.clear();
            level = scanned;
            since = now;
        }
        Boolean known = ABSENT.get(type);
        if (known != null) return known;
        boolean absent = true;
        for (Entity entity : entities.getAll()) {
            if (type.isInstance(entity)) {
                absent = false;
                break;
            }
        }
        ABSENT.put(type, absent);
        return absent;
    }
}
