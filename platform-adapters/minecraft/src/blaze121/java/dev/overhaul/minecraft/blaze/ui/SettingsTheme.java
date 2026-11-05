package dev.overhaul.minecraft.blaze.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * One graphite ramp and one accent. The accent marks only what is selected and what waits for a restart; everything
 * else is neutral so the world behind the shader page carries the colour. Sizes sit on a 4 px grid.
 */
final class SettingsTheme {
    static final int SIDEBAR = 0xF2121316;
    static final int CONTENT = 0xE8161719;
    static final int STRIP = 0xD9121316;
    static final int HAIRLINE = 0x1AFFFFFF;
    static final int HOVER = 0x0DFFFFFF;
    static final int ACTIVE = 0x17FFFFFF;
    static final int TRACK = 0xFF34363C;
    static final int TEXT = 0xFFE6E7EA;
    static final int TEXT_2 = 0xFFA0A3A9;
    static final int TEXT_3 = 0xFF6C7078;
    static final int INK = 0xFF121316;
    static final int ACCENT = 0xFFE8A13A;
    static final int ERROR = 0xFFD46A5E;

    static final int ROW = 20;
    static final int HEADER = 24;
    static final int PAD = 8;
    static final int GAP = 12;
    static final int SIDEBAR_WIDTH = 112;
    static final int TOP = 32;

    private SettingsTheme() {
    }

    /** Text vertically centred in a row of the given height. */
    static void text(GuiGraphics graphics, Font font, Component text, int x, int y, int height, int color) {
        graphics.drawString(font, text, x, y + (height - 8) / 2, color, false);
    }

    static void text(GuiGraphics graphics, Font font, String text, int x, int y, int height, int color) {
        graphics.drawString(font, text, x, y + (height - 8) / 2, color, false);
    }

    static void textRight(GuiGraphics graphics, Font font, String text, int right, int y, int height, int color) {
        graphics.drawString(font, text, right - font.width(text), y + (height - 8) / 2, color, false);
    }

    static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** A 16 by 8 switch whose right edge sits at {@code right}; on is off-white with a dark knob. */
    static void toggle(GuiGraphics graphics, int right, int y, int height, boolean on, boolean enabled) {
        int top = y + (height - 8) / 2, left = right - 16;
        int track = !enabled ? TRACK : on ? TEXT : TRACK;
        graphics.fill(left, top, right, top + 8, track);
        int knob = on ? right - 7 : left + 1;
        graphics.fill(knob, top + 1, knob + 6, top + 7, on ? INK : enabled ? TEXT_2 : TEXT_3);
    }

    /** The selection mark: a 2 px accent bar on the left edge of a row. */
    static void selected(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, ACTIVE);
        graphics.fill(x, y + 4, x + 2, y + height - 4, ACCENT);
    }
}
