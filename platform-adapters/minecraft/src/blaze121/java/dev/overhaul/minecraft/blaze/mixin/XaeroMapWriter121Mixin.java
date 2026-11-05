package dev.overhaul.minecraft.blaze.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Xaero lets its map writer take 8.7% of the render thread's time; a quarter of that fills the map more slowly and leaves the frame alone. */
@Pseudo
@Mixin(targets = "xaero.map.MapWriter", remap = false)
abstract class XaeroMapWriter121Mixin {
    private static final long NANOS_OF_WRITING_PER_MILLISECOND = 21740L;

    @ModifyConstant(method = "onRender", constant = @Constant(longValue = 86960L), require = 0)
    private long overhaul$writeBudget(long original) {
        return NANOS_OF_WRITING_PER_MILLISECOND;
    }
}
