package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.renderer.EffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EffectInstance.class)
public interface EffectInstanceProgram121Access {
    @Accessor("lastProgramId")
    static void overhaul$last(int program) {
        throw new AssertionError();
    }
}
