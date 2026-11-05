package dev.overhaul.minecraft.blaze.mixin;

import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "foundry.veil.impl.client.render.shader.uniform.ShaderUniformImpl", remap = false)
public interface VeilUniform121Access {
    @Accessor(value = "value", remap = false)
    ByteBuffer overhaul$value();
}
