package dev.overhaul.minecraft.blaze;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Values worked out once per client game tick and dropped when the level's game time moves on or the level changes.
 * Render thread only; {@link #get} answers null off it or outside a level, so callers fall back to the uncached path.
 */
final class BlazeTickMemo<K, V> {
    private final int limit;
    private final Map<K, V> values = new HashMap<>();
    private ClientLevel level;
    private long tick = Long.MIN_VALUE;

    BlazeTickMemo(int limit) {
        this.limit = limit;
    }

    /** The key's value for this tick, from {@code work} the first time; a null from {@code work} is not kept. */
    V get(K key, Function<K, V> work) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        if (current == null || !minecraft.isSameThread()) return null;
        long now = current.getGameTime();
        if (current != level || now != tick || values.size() >= limit) {
            values.clear();
            level = current;
            tick = now;
        }
        V value = values.get(key);
        if (value == null) {
            value = work.apply(key);
            if (value != null) values.put(key, value);
        }
        return value;
    }
}
