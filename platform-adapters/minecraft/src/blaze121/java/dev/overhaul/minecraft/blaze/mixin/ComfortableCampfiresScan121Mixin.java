package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.overhaul.minecraft.blaze.BlazeBlockSearch;
import java.util.LinkedHashMap;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Comfortable Campfires streams every block in a box around each player every tick, looking for a campfire or another
 * block it gives an effect for. Only those blocks do anything, so when no section the box covers has one in its
 * palette the stream would yield nothing that matters, and an empty one is returned instead. The original streams
 * nothing either while a chunk in the box is not loaded.
 */
@Pseudo
@Mixin(targets = "cc.cassian.campfire.ComfortableCampfires", remap = false)
abstract class ComfortableCampfiresScan121Mixin {
    @Shadow @Final public static LinkedHashMap<Block, Holder<MobEffect>> EFFECT_MAP;

    @WrapOperation(method = "applyPlayerEffects(Lnet/minecraft/world/entity/player/Player;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockStatesIfLoaded(Lnet/minecraft/world/phys/AABB;)Ljava/util/stream/Stream;"),
            require = 0)
    private static Stream<BlockState> overhaul$skipBarrenBox(Level level, AABB box, Operation<Stream<BlockState>> states) {
        LinkedHashMap<Block, Holder<MobEffect>> effects = EFFECT_MAP;
        if (BlazeBlockSearch.mayHold(level, Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ),
                state -> effects.containsKey(state.getBlock()), true)) {
            return states.call(level, box);
        }
        return Stream.empty();
    }
}
