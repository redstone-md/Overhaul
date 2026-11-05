package dev.overhaul.minecraft.blaze;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.world.level.Level;

/**
 * Answers a mod's per-frame entity scan once per game tick, as entities only come and go between ticks. For scans around
 * the camera whose results are only read; what they read from each entity stays live. Render-thread only.
 */
public final class BlazeTickScan {
    private final Map<Class<?>, List<?>> results = new HashMap<>();
    private Level level;
    private long tick;

    @SuppressWarnings("unchecked")
    public <T> List<T> of(Level level, Class<T> type, Supplier<List<T>> scan) {
        long now = level.getGameTime();
        if (level != this.level || now != tick) {
            results.clear();
            this.level = level;
            tick = now;
        }
        List<?> known = results.get(type);
        if (known != null) return (List<T>) known;
        List<T> found = scan.get();
        results.put(type, found);
        return found;
    }
}
