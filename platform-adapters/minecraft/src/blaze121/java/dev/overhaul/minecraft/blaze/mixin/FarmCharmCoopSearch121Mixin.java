package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeBlockSearch;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A grown chicken in Farm and Charm with no coop in mind looks for one every second or two by asking each block of a
 * 33 by 33 by 9 diamond around it whether it is a chicken coop with room. Only a coop block can answer yes, so when no
 * section the diamond covers has one in its palette, and every chunk it covers is loaded, the walk finds nothing and
 * is skipped. A chunk not loaded may load during the walk, so then the walk runs.
 */
@Pseudo
@Mixin(targets = "net.satisfy.farm_and_charm.core.entity.ai.ChickenLocateCoopGoal", remap = false)
abstract class FarmCharmCoopSearch121Mixin {
    @Unique private static final ResourceLocation OVERHAUL$COOP = ResourceLocation.fromNamespaceAndPath("farm_and_charm", "chicken_coop");
    @Unique private static Block overhaul$coop;

    @Shadow @Final private Chicken chicken;

    @WrapOperation(method = "canUse()Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;findClosestMatch(Lnet/minecraft/core/BlockPos;IILjava/util/function/Predicate;)Ljava/util/Optional;"),
            require = 0)
    private Optional<BlockPos> overhaul$skipBarrenSearch(BlockPos center, int horizontal, int vertical, Predicate<BlockPos> usable,
            Operation<Optional<BlockPos>> search) {
        Block coop = overhaul$coop;
        if (coop == null) {
            coop = BuiltInRegistries.BLOCK.getOptional(OVERHAUL$COOP).orElse(null);
            if (coop == null) return search.call(center, horizontal, vertical, usable);
            overhaul$coop = coop;
        }
        Block wanted = coop;
        if (BlazeBlockSearch.mayHold(chicken.level(), center, horizontal, vertical, vertical, state -> state.is(wanted), true)) {
            return search.call(center, horizontal, vertical, usable);
        }
        return Optional.empty();
    }
}
