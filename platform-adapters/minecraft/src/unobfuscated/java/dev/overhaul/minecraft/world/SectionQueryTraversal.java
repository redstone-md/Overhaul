package dev.overhaul.minecraft.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongBidirectionalIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Seeks within the game's authoritative section tree while retaining callback-prefetch semantics. */
public final class SectionQueryTraversal<T extends EntityAccess> {
    private static final long MAX_QUERY_CELLS = 256;
    private static final long MAX_INTERVAL_SEEKS = 32;
    private final Thread owner = Thread.currentThread();
    private final Long2ObjectMap<EntitySection<T>> sections;
    private final LongSortedSet sectionIds;
    private final SectionColumnOccupancy occupancy;
    private final boolean diagnostics;
    private long structureEpoch;
    private volatile boolean foreignMutation;
    private int vanillaDepth;
    private long queries, fallbacks, seeks, visitedKeys, mutationFallbacks, acceptedSections, vanillaBypasses;
    private long columnDrains;

    public SectionQueryTraversal(Long2ObjectMap<EntitySection<T>> sections, LongSortedSet sectionIds) {
        this(sections, sectionIds, Boolean.getBoolean("overhaul.diagnostics.enable"));
    }

    public SectionQueryTraversal(Long2ObjectMap<EntitySection<T>> sections, LongSortedSet sectionIds,
                                 boolean diagnostics) {
        this.sections = Objects.requireNonNull(sections, "sections");
        this.sectionIds = Objects.requireNonNull(sectionIds, "sectionIds");
        this.occupancy = new SectionColumnOccupancy(sections);
        this.diagnostics = diagnostics;
    }

    /** Called before vanilla createSection/remove, including reentrant mutations inside consumers. */
    public void structureChanged() {
        if (Thread.currentThread() != owner) foreignMutation = true;
        else structureEpoch++;
    }

    /** Called at createSection RETURN, after its section key was added to the authoritative tree. */
    public void sectionCreated(long key) {
        if (Thread.currentThread() != owner) { foreignMutation = true; return; }
        if (!foreignMutation) occupancy.created(key);
    }

    /** Called at remove HEAD; missing keys do not reduce the derived occupancy. */
    public void sectionRemoving(long key) {
        structureChanged();
        if (!foreignMutation && sections.containsKey(key)) occupancy.removing(key);
    }

    /** Scoped access to the untouched game method for a runtime oracle; nesting is exception-safe. */
    public void withVanilla(Runnable action) {
        requireOwner();
        Objects.requireNonNull(action, "action");
        vanillaDepth++;
        try { action.run(); } finally { vanillaDepth--; }
    }

    /** Returns false before invoking any consumer when the original game traversal should run. */
    public boolean tryTraverse(AABB box, AbortableIterationConsumer<EntitySection<T>> consumer) {
        if (Thread.currentThread() != owner) return false;
        if (vanillaDepth != 0) { if (diagnostics) vanillaBypasses++; return false; }
        if (foreignMutation || !occupancy.hasLongColumns() || !finite(box)) return fallback();
        int minX = SectionPos.posToSectionCoord(box.minX - 2.0);
        int minY = SectionPos.posToSectionCoord(box.minY - 4.0);
        int minZ = SectionPos.posToSectionCoord(box.minZ - 2.0);
        int maxX = SectionPos.posToSectionCoord(box.maxX + 2.0);
        int maxY = SectionPos.posToSectionCoord(box.maxY + 0.0);
        int maxZ = SectionPos.posToSectionCoord(box.maxZ + 2.0);
        if (!eligible(minX, minY, minZ, maxX, maxY, maxZ)) return fallback();
        long intersectingCells = ((long) maxY - minY + 1) * ((long) maxZ - minZ + 1);
        boolean worthSeeking = false;
        for (int x = minX; x <= maxX; x++) {
            if (occupancy.worthSeeking(x, intersectingCells)) { worthSeeking = true; break; }
        }
        if (!worthSeeking) return fallback();
        if (diagnostics) queries++;
        for (int x = minX; x <= maxX; x++) {
            if (!traverseColumn(x, minY, minZ, maxY, maxZ, intersectingCells, consumer)) break;
        }
        return true;
    }

    private boolean traverseColumn(int x, int minY, int minZ, int maxY, int maxZ, long intersectingCells,
                                   AbortableIterationConsumer<EntitySection<T>> consumer) {
        // Full vanilla column bounds are essential: a narrower subset would drop its cached successor.
        LongSortedSet column = sectionIds.subSet(SectionPos.asLong(x, 0, 0),
                SectionPos.asLong(x, -1, -1) + 1);
        if (foreignMutation || !occupancy.worthSeeking(x, intersectingCells)) {
            if (diagnostics) columnDrains++;
            return drain(column.iterator(), minY, minZ, maxY, maxZ, consumer);
        }
        return seekRanges(column, x, minY, minZ, maxY, maxZ, consumer);
    }

    private boolean seekRanges(LongSortedSet column, int x, int minY, int minZ, int maxY, int maxZ,
                               AbortableIterationConsumer<EntitySection<T>> consumer) {
        int z = minZ < 0 && maxZ >= 0 ? 0 : minZ;
        int zCount = maxZ - minZ + 1;
        int ySegments = minY < 0 && maxY >= 0 ? 2 : 1;
        for (int zi = 0; zi < zCount; zi++, z = z == maxZ ? minZ : z + 1) {
            for (int segment = 0; segment < ySegments; segment++) {
                int lowY = ySegments == 1 ? minY : segment == 0 ? 0 : minY;
                int highY = ySegments == 1 ? maxY : segment == 0 ? maxY : -1;
                long low = SectionPos.asLong(x, lowY, z);
                long high = SectionPos.asLong(x, highY, z);
                // iterator(k) starts strictly after k. The minimum packed key cannot be decremented.
                LongBidirectionalIterator iterator = low == Long.MIN_VALUE
                        ? column.iterator() : column.iterator(low - 1);
                if (diagnostics) seeks++;
                while (iterator.hasNext()) {
                    long key = iterator.nextLong();
                    if (diagnostics) visitedKeys++;
                    if (key > high) break;
                    long epoch = structureEpoch;
                    if (!accept(key, consumer)) return false;
                    if (epoch != structureEpoch || foreignMutation) {
                        if (diagnostics) mutationFallbacks++;
                        // nextLong prefetched an Entry before accept, exactly as the untouched game did.
                        return drain(iterator, minY, minZ, maxY, maxZ, consumer);
                    }
                }
            }
        }
        return true;
    }

    private boolean drain(LongBidirectionalIterator iterator, int minY, int minZ, int maxY, int maxZ,
                          AbortableIterationConsumer<EntitySection<T>> consumer) {
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (diagnostics) visitedKeys++;
            int y = SectionPos.y(key), z = SectionPos.z(key);
            if (y >= minY && y <= maxY && z >= minZ && z <= maxZ && !accept(key, consumer)) return false;
        }
        return true;
    }

    private boolean accept(long key, AbortableIterationConsumer<EntitySection<T>> consumer) {
        EntitySection<T> section = sections.get(key);
        if (section == null || section.isEmpty() || !section.getStatus().isAccessible()) return true;
        if (diagnostics) acceptedSections++;
        return !consumer.accept(section).shouldAbort();
    }

    private boolean eligible(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // Exclude the extreme positive X column: vanilla's exclusive upper key would overflow there.
        if (minX < -2097152 || maxX >= 2097151 || minZ < -2097152 || maxZ > 2097151
                || minY < -524288 || maxY > 524287) return false;
        long x = (long) maxX - minX + 1, y = (long) maxY - minY + 1, z = (long) maxZ - minZ + 1;
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_QUERY_CELLS || y > MAX_QUERY_CELLS || z > MAX_QUERY_CELLS) return false;
        long cells = x * y * z;
        int ySegments = minY < 0 && maxY >= 0 ? 2 : 1;
        return cells <= MAX_QUERY_CELLS && x * z * ySegments <= MAX_INTERVAL_SEEKS;
    }

    private static boolean finite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }

    private boolean fallback() { if (diagnostics) fallbacks++; return false; }

    /** Immutable diagnostic snapshot; disabled diagnostics keep counters at zero. */
    public Map<String, Long> snapshot() {
        requireOwner();
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("queries", queries);
        values.put("fallbacks", fallbacks);
        values.put("seeks", seeks);
        values.put("visitedKeys", visitedKeys);
        values.put("mutationFallbacks", mutationFallbacks);
        values.put("acceptedSections", acceptedSections);
        values.put("vanillaBypasses", vanillaBypasses);
        // Retain the historical diagnostic keys without retaining unused sampling state.
        values.put("prefixKeys", 0L);
        values.put("prefixDrains", 0L);
        values.put("columnDrains", columnDrains);
        values.put("columns", (long) occupancy.columns());
        values.put("longColumns", (long) occupancy.longColumns());
        return Collections.unmodifiableMap(values);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("section queries belong to their owner thread");
    }
}
