package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.ui.OverhaulSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Video settings open the Overhaul screen, which keeps a way back to the game's own one. */
@Mixin(Minecraft.class)
abstract class VideoSettingsRedirectBlazeMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen overhaul$settings(Screen screen) {
        return OverhaulSettingsScreen.replace(screen);
    }
}
