package dev.overhaul.minecraft.blaze;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Keeps each entity's saved NBT for the rest of the game tick. Entity Texture Features tests NBT-based texture and
 * model rules by saving the whole entity, and it keeps the result only until the next entity is drawn, so a player in
 * a large mod pack is saved again for the hand, the shadow pass and every other draw of every frame. With
 * {@link BlazeOptimization#ENTITY_NBT_TICK} a save is reused until the level's game time moves on; the data a rule
 * reads changes with ticks and packets, so it is at most one tick old.
 */
public final class BlazeEntityNbt {
    private static final int LIMIT = 512;
    private static final Map<UUID, CompoundTag> saved = new HashMap<>();
    private static ClientLevel level;
    private static long tick = Long.MIN_VALUE;

    private BlazeEntityNbt() {
    }

    /** The entity's NBT for this tick, saved by {@code save} the first time; null when the cache is off. */
    public static CompoundTag get(UUID id, Supplier<CompoundTag> save) {
        if (id == null || !BlazeOptimization.ENTITY_NBT_TICK.on()) return null;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        if (current == null || !minecraft.isSameThread()) return null;
        long now = current.getGameTime();
        if (current != level || now != tick || saved.size() >= LIMIT) {
            saved.clear();
            level = current;
            tick = now;
        }
        CompoundTag tag = saved.get(id);
        if (tag == null) {
            tag = save.get();
            if (tag != null) saved.put(id, tag);
        }
        return tag;
    }
}
