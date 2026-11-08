package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import dev.overhaul.minecraft.blaze.BlazeDhStrings;
import it.unimi.dsi.fastutil.chars.CharArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stored LODs name their blocks and biomes through a string pool that walks a trie a character at a time. The pooled
 * string a thread was last given for the same characters is handed back first (see {@link BlazeDhStrings}), and
 * only a miss walks the trie.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.util.objects.pooling.StringPool", remap = false)
abstract class DhStringPool121Mixin {
    @Inject(method = "getPooledString(Lit/unimi/dsi/fastutil/chars/CharArrayList;II)Ljava/lang/String;", at = @At("HEAD"), cancellable = true, require = 0)
    private void overhaul$kept(CharArrayList chars, int offset, int length, CallbackInfoReturnable<String> callback, @Share("generation") LocalIntRef generation) {
        int current = BlazeDhStrings.generation();
        generation.set(current);
        String kept = BlazeDhStrings.current().find(chars, offset, length, current);
        if (kept != null) callback.setReturnValue(kept);
    }

    @Inject(method = "getPooledString(Lit/unimi/dsi/fastutil/chars/CharArrayList;II)Ljava/lang/String;", at = @At("RETURN"), require = 0)
    private void overhaul$keep(CharArrayList chars, int offset, int length, CallbackInfoReturnable<String> callback, @Share("generation") LocalIntRef generation) {
        BlazeDhStrings.current().keep(chars, offset, length, generation.get(), callback.getReturnValue());
    }

    @Inject(method = "clear", at = @At("HEAD"), require = 0)
    private void overhaul$cleared(CallbackInfo callback) {
        BlazeDhStrings.invalidate();
    }
}
