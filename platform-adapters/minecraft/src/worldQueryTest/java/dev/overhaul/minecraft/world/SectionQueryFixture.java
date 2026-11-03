package dev.overhaul.minecraft.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Oracle calls the untouched resolved game's public method, not a copied traversal implementation. */
final class SectionQueryFixture {
    final EntitySectionStorage<FakeEntity> storage = new EntitySectionStorage<>(FakeEntity.class,
            ignored -> Visibility.TICKING);
    final SectionQueryTraversal<FakeEntity> traversal;
    private final Map<EntitySection<FakeEntity>, Long> keys = new IdentityHashMap<>();
    private int nextId;

    SectionQueryFixture() {
        this(true, true);
    }

    SectionQueryFixture(boolean diagnostics, boolean seedDistantSections) {
        traversal = newTraversal(diagnostics);
        if (seedDistantSections) for (int x = -3; x <= 3; x++) for (int i = 0; i < 40; i++) add(x, 100, 100 + i);
    }

    SectionQueryTraversal<FakeEntity> newTraversal(boolean diagnostics) {
        return new SectionQueryTraversal<>(field("sections"), field("sectionIds"), diagnostics);
    }

    @SuppressWarnings("unchecked") private <V> V field(String name) {
        try {
            Field field = EntitySectionStorage.class.getDeclaredField(name);
            field.setAccessible(true);
            return (V) field.get(storage);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    FakeEntity add(int x, int y, int z) { return add(x, y, z, null); }

    FakeEntity add(int x, int y, int z, AABB bounds) {
        long key = SectionPos.asLong(x, y, z);
        boolean created = storage.getSection(key) == null;
        if (created) traversal.structureChanged();
        EntitySection<FakeEntity> section = storage.getOrCreateSection(key);
        if (created) traversal.sectionCreated(key);
        FakeEntity entity = nextId % 2 == 0 ? new FakeEntity(nextId++, x, y, z, bounds)
                : new MarkerEntity(nextId++, x, y, z, bounds);
        section.add(entity);
        keys.put(section, key);
        return entity;
    }

    void remove(int x, int y, int z) {
        traversal.sectionRemoving(SectionPos.asLong(x, y, z));
        storage.remove(SectionPos.asLong(x, y, z));
    }

    void status(int x, int y, int z, Visibility visibility) {
        storage.getSection(SectionPos.asLong(x, y, z)).updateChunkStatus(visibility);
    }

    void empty(int x, int y, int z) {
        EntitySection<FakeEntity> section = storage.getSection(SectionPos.asLong(x, y, z));
        section.getEntities().toList().forEach(section::remove);
    }

    List<Visit> trace(boolean optimized, AABB query, Mutation mutation, int abortAfter) {
        List<Visit> visits = new ArrayList<>();
        AbortableIterationConsumer<EntitySection<FakeEntity>> consumer = section -> {
            visits.add(new Visit(keys.get(section), section.getEntities().map(FakeEntity::getId).toList()));
            if (mutation != null) mutation.run(this, visits.size());
            return Continuation.abortIf(visits.size() == abortAfter);
        };
        if (!optimized || !traversal.tryTraverse(query, consumer)) storage.forEachAccessibleNonEmptySection(query, consumer);
        return visits;
    }

    List<Integer> entityTrace(boolean optimized, AABB query, EntityTypeTest<FakeEntity, FakeEntity> type,
                              int abortAfter) {
        List<Integer> ids = new ArrayList<>();
        AbortableIterationConsumer<FakeEntity> consumer = entity -> {
            ids.add(entity.getId());
            return Continuation.abortIf(ids.size() == abortAfter);
        };
        if (optimized) {
            AbortableIterationConsumer<EntitySection<FakeEntity>> sections = section -> type == null
                    ? section.getEntities(query, consumer) : section.getEntities(type, query, consumer);
            if (!traversal.tryTraverse(query, sections)) {
                if (type == null) storage.getEntities(query, consumer);
                else storage.getEntities(type, query, consumer);
            }
        } else if (type == null) storage.getEntities(query, consumer);
        else storage.getEntities(type, query, consumer);
        return ids;
    }

    static AABB box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // These interior positions expand to exactly the requested section-coordinate box.
        return new AABB(minX * 16.0 + 2, minY * 16.0 + 4, minZ * 16.0 + 2,
                maxX * 16.0 + 13, maxY * 16.0 + 15, maxZ * 16.0 + 13);
    }

    interface Mutation { void run(SectionQueryFixture fixture, int accepted); }
    record Visit(long key, List<Integer> ids) {}

    static class FakeEntity implements EntityAccess {
        private final int id;
        private final BlockPos position;
        private final AABB bounds;

        FakeEntity(int id, int x, int y, int z, AABB bounds) {
            this.id = id;
            position = new BlockPos(x * 16 + 8, y * 16 + 8, z * 16 + 8);
            this.bounds = bounds == null ? new AABB(position) : bounds;
        }

        @Override public int getId() { return id; }
        @Override public UUID getUUID() { return new UUID(0, id); }
        @Override public boolean isRemoved() { return false; }
        @Override public BlockPos blockPosition() { return position; }
        @Override public AABB getBoundingBox() { return bounds; }
        @Override public void setLevelCallback(EntityInLevelCallback callback) {}
        @Override public Stream<? extends EntityAccess> getSelfAndPassengers() { return Stream.of(this); }
        @Override public Stream<? extends EntityAccess> getPassengersAndSelf() { return Stream.of(this); }
        @Override public void setRemoved(Entity.RemovalReason reason) {}
        @Override public boolean shouldBeSaved() { return true; }
        @Override public boolean isAlwaysTicking() { return false; }
    }

    static final class MarkerEntity extends FakeEntity {
        MarkerEntity(int id, int x, int y, int z, AABB bounds) { super(id, x, y, z, bounds); }
    }
}
