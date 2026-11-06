package dev.overhaul.minecraft.blaze.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The screen methods whose signatures changed between 1.20 and 1.21, routed to one shape for the shared screen. */
abstract class BlazeScreenBase extends Screen {
    protected BlazeScreenBase(Component title) {
        super(title);
    }

    /** Paints what sits behind the screen's panels. */
    protected abstract void backdrop(GuiGraphics graphics, float partial);

    protected abstract boolean scrolled(double amount);

    /** The title screen's background, for when no world is open behind the screen. */
    protected void noWorldBackground(GuiGraphics graphics, float partial) {
        renderPanorama(graphics, partial);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        backdrop(graphics, partial);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scrolled(scrollY);
    }
}
