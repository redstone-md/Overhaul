package dev.overhaul.minecraft.mixin;

import net.minecraft.client.gui.Gui;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Gui.class)
public interface HudCacheGuiAccessor {
    @Accessor("layerManager")
    GuiLayerManager overhaul$layerManager();
}
