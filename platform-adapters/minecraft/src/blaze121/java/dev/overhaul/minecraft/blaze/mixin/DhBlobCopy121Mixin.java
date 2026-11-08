package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeDhBytes;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;

/**
 * A stored LOD's database blob is copied into a byte list one read call and one list add per byte. The copy is made
 * in blocks instead (see {@link BlazeDhBytes#readAll}), giving the same bytes in the same list.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo", remap = false)
abstract class DhBlobCopy121Mixin {
    @Inject(method = "putAllBytes", at = @At("HEAD"), cancellable = true, require = 0)
    private static void overhaul$copyInBlocks(InputStream input, ByteArrayList reuse, CallbackInfoReturnable<ByteArrayList> callback) throws IOException {
        callback.setReturnValue(BlazeDhBytes.readAll(input, reuse));
    }
}
