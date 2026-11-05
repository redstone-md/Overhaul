package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShaderInstance.class)
public interface ShaderInstanceProgram121Access {
    @Accessor("lastProgramId")
    static int overhaul$last() {
        throw new AssertionError();
    }

    @Accessor("lastProgramId")
    static void overhaul$last(int program) {
        throw new AssertionError();
    }
}
