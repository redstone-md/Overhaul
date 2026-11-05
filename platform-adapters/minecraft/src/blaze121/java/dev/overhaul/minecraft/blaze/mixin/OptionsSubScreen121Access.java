package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen an options page returns to, so the Overhaul screen that replaces video settings returns there too. */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreen121Access {
    @Accessor("lastScreen")
    Screen overhaul$lastScreen();
}
