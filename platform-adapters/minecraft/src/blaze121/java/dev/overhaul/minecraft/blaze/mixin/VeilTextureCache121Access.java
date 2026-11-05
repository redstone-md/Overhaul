package dev.overhaul.minecraft.blaze.mixin;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.shader.program.ShaderTextureCache", remap = false)
public interface VeilTextureCache121Access {
    @Accessor(value = "textures", remap = false)
    Object2LongMap<CharSequence> overhaul$textures();
}
