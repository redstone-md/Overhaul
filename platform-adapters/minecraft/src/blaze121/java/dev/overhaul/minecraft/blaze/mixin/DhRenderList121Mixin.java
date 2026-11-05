package dev.overhaul.minecraft.blaze.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashSet;

/**
 * Distant Horizons rebuilds its list of visible buffers every frame and hashes each one to keep it unique. The list is
 * filled from the quad tree's sections, each owning its buffer, so the check never finds a duplicate.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.util.objects.SortedArraySet", remap = false)
abstract class DhRenderList121Mixin {
    @Redirect(method = "add", at = @At(value = "INVOKE", target = "Ljava/util/HashSet;add(Ljava/lang/Object;)Z"), require = 0)
    private boolean overhaul$alwaysNew(HashSet<Object> set, Object element) {
        return true;
    }
}
