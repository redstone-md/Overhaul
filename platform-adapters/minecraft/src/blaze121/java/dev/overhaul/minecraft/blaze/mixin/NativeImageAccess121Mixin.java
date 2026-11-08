package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The address of a {@link NativeImage}'s pixels, so whole rows can be copied at once. */
@Mixin(NativeImage.class)
public interface NativeImageAccess121Mixin {
    @Accessor("pixels")
    long overhaul$pixels();
}
