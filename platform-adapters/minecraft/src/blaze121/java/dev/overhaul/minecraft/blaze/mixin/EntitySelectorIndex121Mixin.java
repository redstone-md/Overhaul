package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeLookupIndexed;
import dev.overhaul.minecraft.blaze.BlazeSelectorTags;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An {@code @e} or {@code @n} scan of a whole level that requires a tag, a type or a type tag walks only the entities
 * meeting the narrowest of them, in the level's own order, when they are few; every selector option still tests each.
 * Scans bounded by a box keep vanilla's section walk.
 */
@Mixin(EntitySelector.class)
abstract class EntitySelectorIndex121Mixin {
    @WrapOperation(method = "addEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Ljava/util/function/Predicate;Ljava/util/List;I)V"),
            require = 1)
    @SuppressWarnings("unchecked")
    private void overhaul$walkIndexed(ServerLevel level, EntityTypeTest<Entity, ?> type, Predicate<?> predicate, List<?> found, int limit, Operation<Void> scan) {
        BlazeSelectorTags selector = (BlazeSelectorTags) (Object) this;
        String[] tags = selector.overhaul$requiredTags();
        TagKey<EntityType<?>>[] typeTags = selector.overhaul$requiredTypeTags();
        if (tags != null || typeTags != null || type instanceof EntityType<?>) {
            EntityLookup<Entity> lookup = ((PersistentEntityLookup121Access<Entity>) (Object) ((ServerLevelEntityManager121Access) level).overhaul$entityManager()).overhaul$visible();
            if (((BlazeLookupIndexed) lookup).overhaul$index().select(tags, selector.overhaul$excludedTags(), typeTags, lookup.count(), type, predicate, found, limit)) return;
        }
        scan.call(level, type, predicate, found, limit);
    }
}
