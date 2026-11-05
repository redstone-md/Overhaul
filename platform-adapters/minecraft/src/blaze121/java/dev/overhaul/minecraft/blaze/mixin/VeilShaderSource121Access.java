package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.shader.compiler.VeilShaderSource", remap = false)
public interface VeilShaderSource121Access {
    @Accessor(value = "sourceId", remap = false)
    ResourceLocation overhaul$sourceId();

    @Accessor(value = "sourceCode", remap = false)
    String overhaul$sourceCode();
}
