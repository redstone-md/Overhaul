package dev.overhaul.minecraft.blaze.mixin;

import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The screen an options page returns to, so the Overhaul screen that replaces video settings returns there too. */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreen120Access {
    @Accessor("lastScreen")
    Screen overhaul$lastScreen();
}
