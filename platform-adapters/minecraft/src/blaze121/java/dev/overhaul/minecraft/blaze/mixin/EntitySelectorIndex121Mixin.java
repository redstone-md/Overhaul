package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeLookupIndexed;
import dev.overhaul.minecraft.blaze.BlazeSelectorTags;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazeScoreHolders;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An {@code @e} or {@code @n} scan of a whole level that requires a tag, a type or a type tag walks only the entities
 * meeting the narrowest of them, in the level's own order, when they are few; every selector option still tests each.
 * A required score narrows it to the holders of that objective; a missing objective or one nobody holds matches none.
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
        Set<String> scored = overhaul$scored(level, selector.overhaul$requiredScores());
        if (scored != null && scored.isEmpty()) return;
        if (tags != null || typeTags != null || scored != null || type instanceof EntityType<?>) {
            EntityLookup<Entity> lookup = ((PersistentEntityLookup121Access<Entity>) (Object) ((ServerLevelEntityManager121Access) level).overhaul$entityManager()).overhaul$visible();
            if (((BlazeLookupIndexed) lookup).overhaul$index().select(tags, selector.overhaul$excludedTags(), typeTags, lookup.count(), type, predicate, found, limit,
                    scored, lookup, level.players())) return;
        }
        scan.call(level, type, predicate, found, limit);
    }

    /**
     * The holders of the required objective with the fewest, empty when an objective is missing or has no holder, so
     * no entity can match, or null when no score is required or the holders are not kept.
     */
    @Unique
    private Set<String> overhaul$scored(ServerLevel level, String[] objectives) {
        if (objectives == null || !BlazeOptimization.SELECTOR_SCORES.on()) return null;
        Scoreboard board = level.getServer().getScoreboard();
        Set<String> fewest = null;
        for (String name : objectives) {
            Objective objective = board.getObjective(name);
            Set<String> holders = objective == null ? null : ((BlazeScoreHolders) board).overhaul$holders(objective);
            if (holders == null || holders.isEmpty()) return Set.of();
            if (fewest == null || holders.size() < fewest.size()) fewest = holders;
        }
        return fewest;
    }
}
