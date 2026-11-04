package dev.overhaul.minecraft.mixin;

import java.util.List;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiLayerManager.class)
public interface HudCacheGuiLayerManagerAccessor {
    @Accessor("layers")
    List<GuiLayerManager.NamedLayer> overhaul$layers();
}
