package dev.overhaul.minecraft.blaze;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;

/**
 * How many entities that are not removed carry each command tag. Datapack tick functions run {@code @e[tag=...]}
 * scans over every entity of every level each tick, mostly for tags no entity has; a selector that requires a tag
 * whose count is zero matches nothing without scanning. Counts can only run high, never low: an entity dropped
 * without being removed keeps its tags counted, which only costs that tag its shortcut.
 */
public final class BlazeEntityTags {
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

    private BlazeEntityTags() {}

    /** Whether no entity that is not removed carries the tag. */
    public static boolean absent(String tag) {
        AtomicInteger count = COUNTS.get(tag);
        return count == null || count.get() <= 0;
    }

    private static void counted(String tag) {
        COUNTS.computeIfAbsent(tag, key -> new AtomicInteger()).incrementAndGet();
    }

    private static void uncounted(String tag) {
        AtomicInteger count = COUNTS.get(tag);
        if (count != null) count.decrementAndGet();
    }

    /** An entity's tag set that keeps the counts while its entity is not removed, and its lookup's tag index while it is in one. */
    public static final class TagSet extends HashSet<String> {
        private final Entity owner;
        private boolean live = true;
        private BlazeLookupIndex index;
        private long entry;

        public TagSet(Entity owner) {
            this.owner = owner;
        }

        /** The lookup index the entity is in, and when it entered; null and 0 once it left. */
        void indexed(BlazeLookupIndex index, long entry) {
            this.index = index;
            this.entry = entry;
        }

        BlazeLookupIndex index() {
            return index;
        }

        long entry() {
            return entry;
        }

        /** Called as the entity is removed or brought back. */
        public void live(boolean live) {
            if (this.live == live) return;
            this.live = live;
            for (String tag : this) {
                if (live) counted(tag);
                else uncounted(tag);
            }
        }

        @Override
        public boolean add(String tag) {
            if (!super.add(tag)) return false;
            if (live) counted(tag);
            if (index != null) index.tagged(tag, owner);
            return true;
        }

        @Override
        public boolean remove(Object tag) {
            if (!super.remove(tag)) return false;
            if (live) uncounted((String) tag);
            if (index != null) index.untagged((String) tag, owner);
            return true;
        }

        @Override
        public void clear() {
            for (String tag : this) {
                if (live) uncounted(tag);
                if (index != null) index.untagged(tag, owner);
            }
            super.clear();
        }

        @Override
        public boolean addAll(Collection<? extends String> tags) {
            boolean changed = false;
            for (String tag : tags) changed |= add(tag);
            return changed;
        }

        @Override
        public boolean removeIf(Predicate<? super String> filter) {
            boolean changed = false;
            for (Iterator<String> tags = iterator(); tags.hasNext(); ) {
                if (filter.test(tags.next())) {
                    tags.remove();
                    changed = true;
                }
            }
            return changed;
        }

        @Override
        public Iterator<String> iterator() {
            Iterator<String> tags = super.iterator();
            return new Iterator<>() {
                private String last;

                @Override
                public boolean hasNext() {
                    return tags.hasNext();
                }

                @Override
                public String next() {
                    return last = tags.next();
                }

                @Override
                public void remove() {
                    tags.remove();
                    if (live) uncounted(last);
                    if (index != null) index.untagged(last, owner);
                }
            };
        }

        /** A copy is a plain set: it belongs to no entity, so it must not touch the counts. */
        @Override
        public Object clone() {
            return new HashSet<>(this);
        }
    }
}
