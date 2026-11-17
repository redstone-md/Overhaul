package dev.overhaul.minecraft.blaze.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** One line of a settings page: a label on the left, its control on the right, a description for the side panel. */
abstract class SettingsRow {
    private static final int ARROW = 14, NUMBER = 36;
    final String label;
    final String description;
    private BooleanSupplier enabled = () -> true;
    private BooleanSupplier selected = () -> false;
    private BooleanSupplier changed = () -> false;
    private Supplier<String> note = () -> null;

    SettingsRow(String label, String description) {
        this.label = label;
        this.description = description;
    }

    SettingsRow enabledWhen(BooleanSupplier condition) {
        enabled = condition;
        return this;
    }

    SettingsRow selectedWhen(BooleanSupplier condition) {
        selected = condition;
        return this;
    }

    /** Marks the row as holding a value that waits for Apply. */
    SettingsRow changedWhen(BooleanSupplier condition) {
        changed = condition;
        return this;
    }

    /** A second paragraph for the side panel, such as why the row cannot change now. */
    SettingsRow note(Supplier<String> text) {
        note = text;
        return this;
    }

    boolean enabled() {
        return enabled.getAsBoolean();
    }

    boolean selected() {
        return selected.getAsBoolean();
    }

    boolean changed() {
        return changed.getAsBoolean();
    }

    String note() {
        return note.get();
    }

    int height() {
        return SettingsTheme.ROW;
    }

    boolean interactive() {
        return true;
    }

    private static int controlWidth(int width) {
        return SettingsTheme.control(width);
    }

    private static int controlX(int x, int width) {
        return x + width - SettingsTheme.PAD - controlWidth(width);
    }

    void render(GuiGraphics graphics, Font font, int x, int y, int width, boolean hovered, int mouseX, int mouseY) {
        int height = height();
        boolean on = enabled();
        if (hovered) {
            graphics.fill(x, y, x + width, y + height, SettingsTheme.HOVER);
            graphics.fill(x, y, x + 2, y + height, SettingsTheme.ACCENT);
        }
        int control = controlX(x, width);
        String shown = SettingsTheme.clip(font, label, control - x - SettingsTheme.PAD - 12);
        SettingsTheme.text(graphics, font, shown, x + SettingsTheme.PAD, y, height, on ? SettingsTheme.TEXT : SettingsTheme.FAINT);
        if (changed()) SettingsTheme.changed(graphics, x + SettingsTheme.PAD + font.width(shown) + 4, y, height);
        control(graphics, font, control, y + 2, controlWidth(width), height - 4, on, hovered ? mouseX : -1);
    }

    /** Draws the control in its box. {@code mouseX} is -1 unless the pointer is on the row. */
    abstract void control(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled, int mouseX);

    /** Button 0 is the left mouse button, 1 the right. {@code x} and {@code width} are the row's. */
    void click(double mouseX, int x, int width, int button) {
    }

    void drag(double mouseX, int x, int width) {
    }

    boolean draggable() {
        return false;
    }

    private static void segment(GuiGraphics graphics, Font font, String text, int x, int y, int width, int height, boolean chosen, boolean enabled, int mouseX) {
        boolean hovered = enabled && mouseX >= x && mouseX < x + width;
        int fill = chosen ? enabled ? SettingsTheme.ACCENT : SettingsTheme.FIELD_HOVER : hovered ? SettingsTheme.FIELD_HOVER : SettingsTheme.FIELD;
        int ink = chosen ? enabled ? SettingsTheme.INK : SettingsTheme.MUTED : enabled ? hovered ? SettingsTheme.TEXT : SettingsTheme.MUTED : SettingsTheme.FAINT;
        graphics.fill(x, y, x + width, y + height, fill);
        SettingsTheme.textCentre(graphics, font, SettingsTheme.clip(font, text, width - 4), x, y, width, height, ink);
    }

    /** A section label over a hairline; it groups the rows under it and takes no input. */
    static final class Header extends SettingsRow {
        private final Supplier<String> count;

        Header(String text) {
            this(text, () -> "");
        }

        /** @param count a short figure at the right, such as how many of the section's rows are on */
        Header(String text, Supplier<String> count) {
            super(text, "");
            this.count = count;
        }

        @Override
        int height() {
            return SettingsTheme.SECTION;
        }

        @Override
        boolean interactive() {
            return false;
        }

        @Override
        void render(GuiGraphics graphics, Font font, int x, int y, int width, boolean hovered, int mouseX, int mouseY) {
            int left = x + SettingsTheme.PAD, right = x + width - SettingsTheme.PAD, bottom = y + height() - 3;
            graphics.drawString(font, label, left, bottom - 11, SettingsTheme.MUTED, false);
            String figure = count.get();
            if (figure != null && !figure.isEmpty()) graphics.drawString(font, figure, right - font.width(figure), bottom - 11, SettingsTheme.FAINT, false);
            graphics.fill(left, bottom, right, bottom + 1, SettingsTheme.HAIRLINE);
        }

        @Override
        void control(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled, int mouseX) {
        }
    }

    /** Off and On side by side; the chosen one carries the accent. */
    static final class Toggle extends SettingsRow {
        private final SettingsDraft.Setting<Boolean> setting;

        Toggle(String label, String description, SettingsDraft.Setting<Boolean> setting) {
            super(label, description);
            this.setting = setting;
            changedWhen(setting::changed);
        }

        @Override
        void control(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled, int mouseX) {
            int half = (width - 1) / 2;
            boolean on = setting.get();
            segment(graphics, font, OverhaulLang.get("overhaul.ui.off"), x, y, half, height, !on, enabled, mouseX);
            segment(graphics, font, OverhaulLang.get("overhaul.ui.on"), x + half + 1, y, width - half - 1, height, on, enabled, mouseX);
        }

        /** A click on a half picks it; a click on the label flips the value. */
        @Override
        void click(double mouseX, int x, int width, int button) {
            int control = controlX(x, width), half = (controlWidth(width) - 1) / 2;
            if (mouseX < control) setting.set(!setting.get());
            else setting.set(mouseX >= control + half);
        }
    }

    /** One value between two arrows, with a tick per value under it. Right click steps back. */
    static final class Choice<T> extends SettingsRow {
        private final List<T> values;
        private final SettingsDraft.Setting<T> setting;
        private final Function<T, String> format;

        Choice(String label, String description, List<T> values, SettingsDraft.Setting<T> setting, Function<T, String> format) {
            super(label, description);
            this.values = List.copyOf(values);
            this.setting = setting;
            this.format = format;
            changedWhen(setting::changed);
        }

        @Override
        void control(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled, int mouseX) {
            T value = setting.get();
            int index = values.indexOf(value), box = x + ARROW + 1, boxWidth = width - 2 * ARROW - 2;
            arrow(graphics, SettingsIcon.PREV, x, y, height, enabled, mouseX);
            arrow(graphics, SettingsIcon.NEXT, x + width - ARROW, y, height, enabled, mouseX);
            graphics.fill(box, y, box + boxWidth, y + height, SettingsTheme.FIELD);
            int count = values.size(), tick = count == 0 ? 0 : Math.min(7, (boxWidth - 6) / count - 1);
            boolean ticks = tick >= 2;
            String shown = SettingsTheme.clip(font, format.apply(value), boxWidth - 4);
            SettingsTheme.textCentre(graphics, font, shown, box, y, boxWidth, ticks ? height - 2 : height, enabled ? SettingsTheme.TEXT : SettingsTheme.FAINT);
            if (!ticks) return;
            int left = box + (boxWidth - count * (tick + 1) + 1) / 2;
            for (int i = 0; i < count; i++) {
                int lit = i <= index ? enabled ? SettingsTheme.ACCENT : SettingsTheme.FAINT : SettingsTheme.HAIRLINE;
                graphics.fill(left + i * (tick + 1), y + height - 2, left + i * (tick + 1) + tick, y + height - 1, lit);
            }
        }

        private static void arrow(GuiGraphics graphics, SettingsIcon icon, int x, int y, int height, boolean enabled, int mouseX) {
            boolean hovered = enabled && mouseX >= x && mouseX < x + ARROW;
            graphics.fill(x, y, x + ARROW, y + height, hovered ? SettingsTheme.FIELD_HOVER : SettingsTheme.FIELD);
            icon.draw(graphics, x, y, ARROW, height, enabled ? hovered ? SettingsTheme.TEXT : SettingsTheme.MUTED : SettingsTheme.FAINT, 1);
        }

        @Override
        void click(double mouseX, int x, int width, int button) {
            if (values.isEmpty()) return;
            int control = controlX(x, width), index = values.indexOf(setting.get());
            boolean back = mouseX >= control && mouseX < control + ARROW || button == 1 && mouseX < control + controlWidth(width) - ARROW;
            int next = index < 0 ? back ? values.size() - 1 : 0 : Math.floorMod(index + (back ? -1 : 1), values.size());
            setting.set(values.get(next));
        }
    }

    /** A track with a square thumb and the value beside it; press or drag on the track to set. */
    static final class Slider extends SettingsRow {
        private final int minimum, maximum, step;
        private final SettingsDraft.Setting<Integer> setting;
        private final IntFunction<String> format;

        Slider(String label, String description, int minimum, int maximum, int step, SettingsDraft.Setting<Integer> setting, IntFunction<String> format) {
            super(label, description);
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.setting = setting;
            this.format = format;
            changedWhen(setting::changed);
        }

        @Override
        void control(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled, int mouseX) {
            int value = setting.get(), track = width - NUMBER, middle = y + height / 2;
            int thumb = x + (int) Math.round((track - 3) * Mth.clamp((value - minimum) / (double) Math.max(1, maximum - minimum), 0, 1));
            graphics.fill(x, middle - 1, x + track, middle + 1, SettingsTheme.FIELD_HOVER);
            graphics.fill(x, middle - 1, thumb, middle + 1, enabled ? SettingsTheme.ACCENT : SettingsTheme.FAINT);
            graphics.fill(thumb, middle - 5, thumb + 3, middle + 5, enabled ? SettingsTheme.TEXT : SettingsTheme.FAINT);
            SettingsTheme.textRight(graphics, font, SettingsTheme.clip(font, format.apply(value), NUMBER + 20), x + width, y, height, enabled ? SettingsTheme.TEXT : SettingsTheme.FAINT);
        }

        @Override
        void click(double mouseX, int x, int width, int button) {
            if (mouseX >= controlX(x, width) - 4) drag(mouseX, x, width);
        }

        @Override
        void drag(double mouseX, int x, int width) {
            int left = controlX(x, width), span = Math.max(1, controlWidth(width) - NUMBER - 3);
            double fraction = Mth.clamp((mouseX - left - 1) / span, 0, 1);
            int value = Mth.clamp(minimum + (int) Math.round(fraction * (maximum - minimum) / step) * step, minimum, maximum);
            if (value != setting.get()) setting.set(value);
        }

        @Override
        boolean draggable() {
            return true;
        }
    }

    /** A button that runs something; it carries the accent while the row is selected. */
    static final class Action extends SettingsRow {
        private final SettingsIcon icon;
        private final Supplier<String> caption;
        private final Runnable run;

        /** @param icon drawn before the caption, or null */
        Action(String label, String description, SettingsIcon icon, Supplier<String> caption, Runnable run) {
            super(label, description);
            this.icon = icon;
            this.caption = caption;
            this.run = run;
        }

        @Override
        void control(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled, int mouseX) {
            boolean chosen = selected(), hovered = enabled && mouseX >= 0;
            graphics.fill(x, y, x + width, y + height, chosen ? enabled ? SettingsTheme.ACCENT : SettingsTheme.FIELD_HOVER : hovered ? SettingsTheme.FIELD_HOVER : SettingsTheme.FIELD);
            int ink = chosen && enabled ? SettingsTheme.INK : enabled ? SettingsTheme.TEXT : SettingsTheme.FAINT;
            int side = icon == null ? 0 : SettingsIcon.side(1);
            String text = caption.get();
            text = text == null ? "" : SettingsTheme.clip(font, text, width - side - 10);
            int gap = icon != null && !text.isEmpty() ? 4 : 0, left = x + (width - side - gap - font.width(text)) / 2;
            if (icon != null) icon.draw(graphics, left, y, side, height, ink, 1);
            SettingsTheme.text(graphics, font, text, left + side + gap, y, height, ink);
        }

        @Override
        void click(double mouseX, int x, int width, int button) {
            if (button == 0) run.run();
        }
    }

    /** A value the screen only reports. */
    static final class Info extends SettingsRow {
        private final Supplier<String> value;

        Info(String label, String description, Supplier<String> value) {
            super(label, description);
            this.value = value;
        }

        @Override
        void control(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled, int mouseX) {
            String text = value.get();
            if (text != null) SettingsTheme.textRight(graphics, font, SettingsTheme.clip(font, text, width), x + width, y, height, SettingsTheme.MUTED);
        }
    }
}
