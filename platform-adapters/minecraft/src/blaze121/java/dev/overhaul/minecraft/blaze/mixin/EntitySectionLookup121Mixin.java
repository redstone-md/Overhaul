package dev.overhaul.minecraft.blaze.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An entity query walks every section in each x slab of the world's sorted section set, then keeps those inside its
 * box. A small box looks its few sections up by key instead, in the order the sorted walk would have met them, so a
 * query that stops early or collects a list sees the same entities in the same order.
 */
@Mixin(EntitySectionStorage.class)
abstract class EntitySectionLookup121Mixin<T extends EntityAccess> {
    private static final int OVERHAUL_MAX_LOOKUPS = 64;

    @Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;

    @Inject(method = "forEachAccessibleNonEmptySection", at = @At("HEAD"), cancellable = true, require = 1)
    private void overhaul$lookUpSections(AABB box, AbortableIterationConsumer<EntitySection<T>> consumer, CallbackInfo callback) {
        // The same margins as the walk: entities are filed by position, so their boxes reach into neighbour sections.
        int minX = SectionPos.posToSectionCoord(box.minX - 2.0), maxX = SectionPos.posToSectionCoord(box.maxX + 2.0);
        int minY = SectionPos.posToSectionCoord(box.minY - 4.0), maxY = SectionPos.posToSectionCoord(box.maxY);
        int minZ = SectionPos.posToSectionCoord(box.minZ - 2.0), maxZ = SectionPos.posToSectionCoord(box.maxZ + 2.0);
        long count = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (count <= 0 || count > OVERHAUL_MAX_LOOKUPS) return;
        callback.cancel();
        // Keys hold z and y as unsigned bits, so the sorted walk meets 0 and up before the negative coordinates.
        for (int x = minX; x <= maxX; x++) {
            if (overhaul$zRun(x, Math.max(minZ, 0), maxZ, minY, maxY, consumer)) return;
            if (overhaul$zRun(x, minZ, Math.min(maxZ, -1), minY, maxY, consumer)) return;
        }
    }

    private boolean overhaul$zRun(int x, int fromZ, int toZ, int minY, int maxY, AbortableIterationConsumer<EntitySection<T>> consumer) {
        for (int z = fromZ; z <= toZ; z++) {
            if (overhaul$yRun(x, z, Math.max(minY, 0), maxY, consumer)) return true;
            if (overhaul$yRun(x, z, minY, Math.min(maxY, -1), consumer)) return true;
        }
        return false;
    }

    private boolean overhaul$yRun(int x, int z, int fromY, int toY, AbortableIterationConsumer<EntitySection<T>> consumer) {
        for (int y = fromY; y <= toY; y++) {
            EntitySection<T> section = sections.get(SectionPos.asLong(x, y, z));
            if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && consumer.accept(section).shouldAbort()) return true;
        }
        return false;
    }
}
