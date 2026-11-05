package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Shader packs read the block atlas size, which the game keeps package-private. */
@Mixin(TextureAtlas.class)
public interface TextureAtlas121Accessor {
    @Invoker("getWidth")
    int overhaul$width();

    @Invoker("getHeight")
    int overhaul$height();
}
