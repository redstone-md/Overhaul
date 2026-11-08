package dev.overhaul.minecraft.blaze.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.chunk.HashMapPalette;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Saving a chunk section packs its states into a fresh hash palette and lists its entries, which walked the palette's
 * whole id array through a filtering iterator. Palette ids are handed out in order from zero, so the entries are the
 * first {@code size} ids; a gap, which never happens, leaves the list to the original walk.
 */
@Mixin(HashMapPalette.class)
abstract class HashMapPaletteEntries121Mixin<T> {
    @Shadow @Final private CrudeIncrementalIntIdentityHashBiMap<T> values;

    @Inject(method = "getEntries", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$listInOrder(CallbackInfoReturnable<List<T>> result) {
        CrudeIncrementalIntIdentityHashBiMap<T> ids = values;
        int size = ids.size();
        List<T> entries = new ArrayList<>(size);
        for (int id = 0; id < size; id++) {
            T value = ids.byId(id);
            if (value == null) return;
            entries.add(value);
        }
        result.setReturnValue(entries);
    }
}
