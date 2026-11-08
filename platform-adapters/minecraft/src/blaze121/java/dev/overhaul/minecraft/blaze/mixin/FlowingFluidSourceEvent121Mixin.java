package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeEventPresence;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.block.CreateFluidSourceEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Water spreading through a freshly generated ocean asks at nearly every neighbour whether it may become a source,
 * and each ask built and posted an event. While nothing listens to that event, its answer is the fluid's own, which
 * is what the event starts with, so the fluid is asked directly.
 */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidSourceEvent121Mixin {
    @Unique private static final BlazeEventPresence OVERHAUL$LISTENED = new BlazeEventPresence(CreateFluidSourceEvent.class);

    @Redirect(method = "getNewLiquid", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;canCreateFluidSource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"),
            require = 0)
    private boolean overhaul$canCreateSource(Level level, BlockPos pos, BlockState state) {
        if (OVERHAUL$LISTENED.heard()) return EventHooks.canCreateFluidSource(level, pos, state);
        return state.getFluidState().canConvertToSource(level, pos);
    }
}
