package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEntityTags;
import dev.overhaul.minecraft.blaze.BlazeSelectorTags;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.world.entity.Entity;
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

    @Override
    public void overhaul$requiredTags(String[] tags) {
        overhaul$requiredTags = tags;
    }

    @Inject(method = "findEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/EntitySelector;checkPermissions(Lnet/minecraft/commands/CommandSourceStack;)V",
            shift = At.Shift.AFTER), cancellable = true, require = 1)
    private void overhaul$skipAbsentTags(CommandSourceStack source, CallbackInfoReturnable<List<? extends Entity>> info) {
        String[] tags = overhaul$requiredTags;
        if (tags == null || !includesEntities || currentEntity || playerName != null || entityUUID != null) return;
        for (String tag : tags) {
            if (BlazeEntityTags.absent(tag)) {
                info.setReturnValue(List.of());
                return;
            }
        }
    }
}
