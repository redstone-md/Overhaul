package dev.overhaul.minecraft.blaze.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.overhaul.minecraft.blaze.BlazeVeilPrograms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla selects programs through ProgramManager; only mods that bind their own come through here. */
@Mixin(GlStateManager.class)
abstract class GlStateProgram121Mixin {
    @Inject(method = "_glUseProgram", at = @At("HEAD"), remap = false, require = 1)
    private static void overhaul$use(int program, CallbackInfo callback) {
        BlazeVeilPrograms.use(program);
    }
}
