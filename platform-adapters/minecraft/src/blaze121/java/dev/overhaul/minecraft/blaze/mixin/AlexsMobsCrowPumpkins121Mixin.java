package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeBlockSearch;
import dev.overhaul.minecraft.blaze.BlazeOuterInstance;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A wild crow in Alex's Mobs looks for a pumpkin to flee every few seconds by asking each of up to 39 by 39 columns of
 * eleven blocks around it whether that block is one crows fear. When no section the walk covers has such a block in its
 * palette, and every chunk it covers is loaded, the walk cannot find one and has nothing else to do, so it answers no
 * without walking. A chunk not loaded may load during the walk, so then the walk runs.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.alexsmobs.entity.EntityCrow$AIAvoidPumpkins", remap = false)
abstract class AlexsMobsCrowPumpkins121Mixin {
    @Unique private static final TagKey<Block> OVERHAUL$CROW_FEARS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("alexsmobs", "crow_fears"));

    @Shadow @Final private int searchLength;

    @Inject(method = "searchForDestination()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$skipBarrenWalk(CallbackInfoReturnable<Boolean> result) {
        if (!(BlazeOuterInstance.of(this) instanceof Entity crow)) return;
        if (!BlazeBlockSearch.mayHold(crow.level(), crow.blockPosition(), searchLength - 1, 9, 1,
                state -> state.is(OVERHAUL$CROW_FEARS), true)) {
            result.setReturnValue(false);
        }
    }
}
