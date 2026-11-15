package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.overhaul.minecraft.blaze.BlazeMipColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fills a sprite's transparent pixels before its mip levels are built from them. Every constructor is matched, since
 * each loader adds its own metadata argument. Leaves keep theirs: with fast graphics they draw transparent pixels opaque.
 */
@Mixin(SpriteContents.class)
abstract class SpriteMipFillBlazeMixin {
    @Shadow @Final private ResourceLocation name;
    @Shadow @Final private NativeImage originalImage;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void overhaul$fillTransparent(CallbackInfo callback) {
        if (Minecraft.getInstance().options.mipmapLevels().get() > 0 && !name.getPath().contains("leaves")) {
            BlazeMipColors.fillTransparent(originalImage);
        }
    }
}
