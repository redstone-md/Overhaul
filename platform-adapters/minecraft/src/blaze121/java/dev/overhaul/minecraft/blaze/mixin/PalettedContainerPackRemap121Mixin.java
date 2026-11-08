package dev.overhaul.minecraft.blaze.mixin;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Packing a section maps each of its four thousand ids to the id the saved palette gives the same block, asking the
 * palettes again whenever the id differs from the one before it. A section holds only a few ids, so each is asked
 * once, in the order it first appears, which is the order the saved palette is built in either way.
 */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerPackRemap121Mixin {
    /** Ids from small palettes; a larger id is asked every time, as before. */
    @Unique private static final int OVERHAUL$TABLE = 256;

    @Redirect(method = "pack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainer;swapPalette([ILjava/util/function/IntUnaryOperator;)V"),
            require = 0)
    private void overhaul$remapOnce(int[] ids, IntUnaryOperator remap) {
        int[] table = new int[OVERHAUL$TABLE];
        Arrays.fill(table, -1);
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            if (id >= OVERHAUL$TABLE || id < 0) {
                ids[i] = remap.applyAsInt(id);
                continue;
            }
            int mapped = table[id];
            if (mapped < 0) mapped = table[id] = remap.applyAsInt(id);
            ids[i] = mapped;
        }
    }
}
