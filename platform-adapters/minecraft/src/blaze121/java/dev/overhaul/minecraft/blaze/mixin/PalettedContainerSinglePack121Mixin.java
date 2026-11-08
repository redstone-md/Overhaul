package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeSectionPalettes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Saving a chunk packs every section, and a section of one block, most often air, was unpacked into four thousand
 * ids, remapped one by one and packed back into nothing. Its packed form is that one block with no stored indices,
 * which is what the full path ends with for a single value, so it is written directly.
 */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerSinglePack121Mixin<T> {
    @Shadow public abstract void acquire();
    @Shadow public abstract void release();

    @Inject(method = "pack", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$packSingle(IdMap<T> registry, PalettedContainer.Strategy strategy, CallbackInfoReturnable<PalettedContainerRO.PackedData<T>> callback) {
        if (!(BlazeSectionPalettes.of((PalettedContainer<?>) (Object) this) instanceof SingleValuePalette<?> palette)) return;
        acquire();
        try {
            @SuppressWarnings("unchecked") T value = (T) palette.valueFor(0);
            List<T> entries = new ArrayList<>(1);
            entries.add(value);
            callback.setReturnValue(new PalettedContainerRO.PackedData<>(entries, Optional.empty()));
        } finally {
            release();
        }
    }
}
