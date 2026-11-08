package dev.overhaul.minecraft.blaze;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * The entities of one entity lookup by command tag and by type, with the order they entered it. A datapack's
 * {@code @e[tag=...]} or {@code @e[type=...]} scan walks every entity of the lookup; when the entities that can match
 * are few it walks just them, in the lookup's own order, so the same entities are selected in the same order. A type
 * whose every entity carries a tag the selector excludes cannot match and is left out. The index lives with its
 * lookup, so a discarded level takes its index with it; it is used only from the thread that owns the lookup.
 */
public final class BlazeLookupIndex {
    private static final Comparator<Entity> ENTRY = Comparator.comparingLong(entity -> ((BlazeEntityTags.TagSet) entity.getTags()).entry());

    private final HashMap<String, Set<Entity>> byTag = new HashMap<>();
    private final HashMap<EntityType<?>, Set<Entity>> byType = new HashMap<>();
    /** How many entities of each type carry each tag. */
    private final HashMap<String, Object2IntOpenHashMap<EntityType<?>>> tagTypes = new HashMap<>();
    private long entries;
    /** Set once an entity joins without the counted tag set, after which the index is never used. */
    private boolean partial;

    /** Called once the entity is in the lookup. */
    public void entered(Entity entity) {
        if (!(entity.getTags() instanceof BlazeEntityTags.TagSet tags)) {
            partial = true;
            return;
        }
        tags.indexed(this, ++entries);
        put(byType, entity.getType(), entity);
        for (String tag : tags) tagged(tag, entity);
    }

    /** Called as the entity leaves the lookup. */
    public void left(Entity entity) {
        if (!(entity.getTags() instanceof BlazeEntityTags.TagSet tags) || tags.index() != this) return;
        tags.indexed(null, 0);
        take(byType, entity.getType(), entity);
        for (String tag : tags) untagged(tag, entity);
    }

    void tagged(String tag, Entity entity) {
        put(byTag, tag, entity);
        tagTypes.computeIfAbsent(tag, ignored -> new Object2IntOpenHashMap<>()).addTo(entity.getType(), 1);
    }

    void untagged(String tag, Entity entity) {
        take(byTag, tag, entity);
        Object2IntOpenHashMap<EntityType<?>> types = tagTypes.get(tag);
        if (types == null) return;
        if (types.addTo(entity.getType(), -1) <= 1) types.removeInt(entity.getType());
        if (types.isEmpty()) tagTypes.remove(tag);
    }

    private static <K> void put(Map<K, Set<Entity>> map, K key, Entity entity) {
        map.computeIfAbsent(key, ignored -> new ReferenceOpenHashSet<>()).add(entity);
    }

    private static <K> void take(Map<K, Set<Entity>> map, K key, Entity entity) {
        Set<Entity> set = map.get(key);
        if (set != null && set.remove(entity) && set.isEmpty()) map.remove(key);
    }

    /**
     * Selects as {@code ServerLevel.getEntities(type, predicate, found, limit)} would, but only among the entities of
     * the narrowest requirement: a required tag, an exact type, or a required type tag. Returns false, selecting
     * nothing, when there is no requirement or its entities are not few enough out of {@code count} to beat the walk.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean select(String[] tags, String[] excluded, TagKey<EntityType<?>>[] typeTags, int count, EntityTypeTest<Entity, ?> type,
            Predicate predicate, List found, int limit) {
        if (partial) return false;
        Set<Entity> tagged = null;
        int size = Integer.MAX_VALUE;
        if (tags != null) {
            for (String tag : tags) {
                Set<Entity> set = byTag.get(tag);
                if (set == null) return true;
                if (set.size() < size) {
                    tagged = set;
                    size = set.size();
                }
            }
        }
        List<EntityType<?>> types = null;
        int typed = Integer.MAX_VALUE;
        if (type instanceof EntityType<?> exact) {
            types = open(List.of(exact), excluded);
            typed = entities(types);
        }
        if (typeTags != null) {
            for (TagKey<EntityType<?>> typeTag : typeTags) {
                List<EntityType<?>> tagTyped = new ArrayList<>();
                for (EntityType<?> key : byType.keySet()) {
                    if (key.is(typeTag)) tagTyped.add(key);
                }
                tagTyped = open(tagTyped, excluded);
                int sum = entities(tagTyped);
                if (sum < typed) {
                    types = tagTyped;
                    typed = sum;
                }
            }
        }
        if (typed == 0) return true;
        Entity[] order;
        if (types != null && typed < size) {
            if (typed * 2L > count) return false;
            order = new Entity[typed];
            int at = 0;
            for (EntityType<?> key : types) {
                for (Entity entity : byType.get(key)) order[at++] = entity;
            }
        } else if (tagged != null) {
            if (size * 2L > count) return false;
            order = tagged.toArray(Entity[]::new);
        } else {
            return false;
        }
        if (order.length > 1) Arrays.sort(order, ENTRY);
        for (Entity entity : order) {
            Object cast = type.tryCast(entity);
            if (cast != null && predicate.test(cast)) {
                found.add(cast);
                if (found.size() >= limit) break;
            }
        }
        return true;
    }

    /** The types that have entities, less those whose every entity carries an excluded tag. */
    private List<EntityType<?>> open(List<EntityType<?>> types, String[] excluded) {
        List<EntityType<?>> open = new ArrayList<>(types.size());
        for (EntityType<?> key : types) {
            Set<Entity> set = byType.get(key);
            if (set != null && !covered(key, set.size(), excluded)) open.add(key);
        }
        return open;
    }

    private boolean covered(EntityType<?> key, int entities, String[] excluded) {
        if (excluded == null) return false;
        for (String tag : excluded) {
            Object2IntOpenHashMap<EntityType<?>> counts = tagTypes.get(tag);
            if (counts != null && counts.getInt(key) >= entities) return true;
        }
        return false;
    }

    private int entities(List<EntityType<?>> types) {
        int sum = 0;
        for (EntityType<?> key : types) sum += byType.get(key).size();
        return sum;
    }
}