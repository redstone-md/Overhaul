package dev.overhaul.minecraft.blaze.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * One dark panel, one lime accent, square edges and no blur. The accent marks what is chosen, what changed and the
 * action that saves; everything else is neutral, so the world behind the screen carries the colour.
 */
final class SettingsTheme {
    static final int ACCENT = 0xFFC6F24E;
    /** Text on the accent. */
    static final int INK = 0xFF141C02;
    static final int TEXT = 0xFFF1F2F4;
    static final int MUTED = 0xFFB6BAC2;
    static final int FAINT = 0xFF767A83;
    static final int HAIRLINE = 0x24FFFFFF;
    static final int HOVER = 0x17FFFFFF;
    static final int FIELD = 0x14FFFFFF;
    static final int FIELD_HOVER = 0x26FFFFFF;
    static final int PANEL = 0xE60E0F12;
    static final int DIM = 0x4D08090B;
    static final int ERROR = 0xFFE5766A;

    static final int HEADER = 36;
    static final int FOOTER = 26;
    static final int ROW = 20;
    static final int SECTION = 22;
    static final int PAD = 8;
    static final int EDGE = 12;

    private SettingsTheme() {
    }

    /** Text vertically centred in a box of the given height. */
    static void text(GuiGraphics graphics, Font font, String text, int x, int y, int height, int color) {
        graphics.drawString(font, text, x, y + (height - 8) / 2, color, false);
    }

    static void textRight(GuiGraphics graphics, Font font, String text, int right, int y, int height, int color) {
        graphics.drawString(font, text, right - font.width(text), y + (height - 8) / 2, color, false);
    }

    static void textCentre(GuiGraphics graphics, Font font, String text, int x, int y, int width, int height, int color) {
        graphics.drawString(font, text, x + (width - font.width(text)) / 2, y + (height - 8) / 2, color, false);
    }

    /** The text, or its start and an ellipsis when it is wider than {@code width}. */
    static String clip(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }

    static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** How wide a key hint draws. */
    static int keycapWidth(Font font, String key) {
        return Math.max(12, font.width(key) + 6);
    }

    /** A key hint: the key's name in a hairline box. Returns the box's width. */
    static int keycap(GuiGraphics graphics, Font font, String key, int x, int y, int height) {
        int width = keycapWidth(font, key), top = y + (height - 12) / 2;
        outline(graphics, x, top, width, 12, HAIRLINE);
        graphics.drawString(font, key, x + (width - font.width(key) + 1) / 2, top + 2, MUTED, false);
        return width;
    }

    /** The mark of a value that waits for Apply. */
    static void changed(GuiGraphics graphics, int x, int y, int height) {
        int top = y + (height - 3) / 2;
        graphics.fill(x, top, x + 3, top + 3, ACCENT);
    }

    /** How wide a row's control is in a list of the given width. */
    static int control(int width) {
        return Math.max(84, Math.min(150, width * 46 / 100));
    }

    static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
