package dev.overhaul.minecraft.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The overlays that blend against the world, drawn live outside the cached HUD. */
@Mixin(Gui.class)
public interface HudCacheGuiInvoker {
    @Accessor("scopeScale")
    float overhaul$scopeScale();

    @Accessor("scopeScale")
    void overhaul$setScopeScale(float scale);

    @Invoker("renderVignette")
    void overhaul$renderVignette(GuiGraphics graphics, Entity entity);

    @Invoker("renderSpyglassOverlay")
    void overhaul$renderSpyglassOverlay(GuiGraphics graphics, float scale);

    @Invoker("renderTextureOverlay")
    void overhaul$renderTextureOverlay(GuiGraphics graphics, ResourceLocation texture, float alpha);

    @Invoker("renderPortalOverlay")
    void overhaul$renderPortalOverlay(GuiGraphics graphics, float intensity);

    @Invoker("renderCrosshair")
    void overhaul$renderCrosshair(GuiGraphics graphics);
}
