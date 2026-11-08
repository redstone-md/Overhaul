package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEntityTags;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazeSelectorTags;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An {@code @e} or {@code @n} scan of the levels that requires a tag no entity carries matches nothing, so it returns
 * before walking every entity. {@code @s}, names and UUIDs keep vanilla's path: they test one entity directly, and
 * that entity may be removed and so not counted.
 */
@Mixin(EntitySelector.class)
abstract class EntitySelectorTags121Mixin implements BlazeSelectorTags {
    @Shadow @Final private boolean includesEntities;
    @Shadow @Final private boolean currentEntity;
    @Shadow @Final private String playerName;
    @Shadow @Final private UUID entityUUID;
    @Unique private String[] overhaul$requiredTags;
    @Unique private String[] overhaul$excludedTags;
    @Unique private TagKey<EntityType<?>>[] overhaul$requiredTypeTags;
    @Unique private String[] overhaul$requiredScores;

    @Override
    public void overhaul$requiredTags(String[] tags) {
        overhaul$requiredTags = tags;
    }

    @Override
    public String[] overhaul$requiredTags() {
        return overhaul$requiredTags;
    }

    @Override
    public void overhaul$excludedTags(String[] tags) {
        overhaul$excludedTags = tags;
    }

    @Override
    public String[] overhaul$excludedTags() {
        return overhaul$excludedTags;
    }

    @Override
    public void overhaul$requiredTypeTags(TagKey<EntityType<?>>[] tags) {
        overhaul$requiredTypeTags = tags;
    }

    @Override
    public TagKey<EntityType<?>>[] overhaul$requiredTypeTags() {
        return overhaul$requiredTypeTags;
    }

    @Override
    public void overhaul$requiredScores(String[] objectives) {
        overhaul$requiredScores = objectives;
    }

    @Override
    public String[] overhaul$requiredScores() {
        return overhaul$requiredScores;
    }

    @Inject(method = "findEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/EntitySelector;checkPermissions(Lnet/minecraft/commands/CommandSourceStack;)V",
            shift = At.Shift.AFTER), cancellable = true, require = 1)
    private void overhaul$skipAbsentTags(CommandSourceStack source, CallbackInfoReturnable<List<? extends Entity>> info) {
        String[] tags = overhaul$requiredTags;
        // Also applied for selectorIndex alone, which needs only the tags noted.
        if (!BlazeOptimization.SELECTOR_TAGS.on() || tags == null || !includesEntities || currentEntity || playerName != null || entityUUID != null) return;
        for (String tag : tags) {
            if (BlazeEntityTags.absent(tag)) {
                info.setReturnValue(List.of());
                return;
            }
        }
    }
}
