package dev.overhaul.minecraft.blaze.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** One line of a settings page: a label on the left, its value on the right, a description for the side panel. */
abstract class SettingsRow {
    final Component label;
    final String description;
    private BooleanSupplier enabled = () -> true;
    private BooleanSupplier selected = () -> false;
    private Supplier<String> note = () -> null;

    SettingsRow(Component label, String description) {
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

    /** A second line for the side panel, such as why the row cannot change now. */
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

    String note() {
        return note.get();
    }

    int height() {
        return SettingsTheme.ROW;
    }

    boolean interactive() {
        return true;
    }

    void render(GuiGraphics graphics, Font font, int x, int y, int width, boolean hovered) {
        int height = height();
        boolean on = enabled();
        if (selected()) SettingsTheme.selected(graphics, x, y, width, height);
        else if (hovered && on) graphics.fill(x, y, x + width, y + height, SettingsTheme.HOVER);
        SettingsTheme.text(graphics, font, label, x + SettingsTheme.PAD, y, height, on ? SettingsTheme.TEXT : SettingsTheme.TEXT_3);
        value(graphics, font, x, y, width, height, on);
    }

    abstract void value(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled);

    /** Button 0 is the left mouse button, 1 the right. */
    void click(double mouseX, int x, int width, int button) {
    }

    void drag(double mouseX, int x, int width) {
    }

    boolean draggable() {
        return false;
    }

    /** A section label; it groups the rows under it and takes no input. */
    static final class Header extends SettingsRow {
        Header(String text) {
            super(Component.literal(text), "");
        }

        @Override
        int height() {
            return SettingsTheme.HEADER;
        }

        @Override
        boolean interactive() {
            return false;
        }

        @Override
        void render(GuiGraphics graphics, Font font, int x, int y, int width, boolean hovered) {
            graphics.drawString(font, label, x + SettingsTheme.PAD, y + 12, SettingsTheme.TEXT_3, false);
        }

        @Override
        void value(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled) {
        }
    }

    static final class Toggle extends SettingsRow {
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;

        Toggle(Component label, String description, BooleanSupplier get, Consumer<Boolean> set) {
            super(label, description);
            this.get = get;
            this.set = set;
        }

        @Override
        void value(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled) {
            SettingsTheme.toggle(graphics, x + width - SettingsTheme.PAD, y, height, get.getAsBoolean(), enabled);
        }

        @Override
        void click(double mouseX, int x, int width, int button) {
            set.accept(!get.getAsBoolean());
        }
    }

    /** Left click steps forward through the values, right click back. */
    static final class Choice<T> extends SettingsRow {
        private final List<T> values;
        private final Supplier<T> get;
        private final Consumer<T> set;
        private final Function<T, String> format;

        Choice(Component label, String description, List<T> values, Supplier<T> get, Consumer<T> set, Function<T, String> format) {
            super(label, description);
            this.values = List.copyOf(values);
            this.get = get;
            this.set = set;
            this.format = format;
        }

        @Override
        void value(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled) {
            SettingsTheme.textRight(graphics, font, format.apply(get.get()), x + width - SettingsTheme.PAD, y, height, enabled ? SettingsTheme.TEXT_2 : SettingsTheme.TEXT_3);
        }

        @Override
        void click(double mouseX, int x, int width, int button) {
            int index = Math.max(0, values.indexOf(get.get()));
            set.accept(values.get(Math.floorMod(index + (button == 1 ? -1 : 1), values.size())));
        }
    }

    /** A whole-row slider: pressing or dragging anywhere on the row sets the value under the pointer. */
    static final class Slider extends SettingsRow {
        private final int minimum, maximum, step;
        private final IntSupplier get;
        private final IntConsumer set;
        private final IntFunction<String> format;

        Slider(Component label, String description, int minimum, int maximum, int step, IntSupplier get, IntConsumer set, IntFunction<String> format) {
            super(label, description);
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.get = get;
            this.set = set;
            this.format = format;
        }

        @Override
        void value(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled) {
            int value = get.getAsInt();
            SettingsTheme.textRight(graphics, font, format.apply(value), x + width - SettingsTheme.PAD, y, height, enabled ? SettingsTheme.TEXT_2 : SettingsTheme.TEXT_3);
            int left = x + SettingsTheme.PAD, right = x + width - SettingsTheme.PAD, bottom = y + height - 2;
            graphics.fill(left, bottom - 2, right, bottom, SettingsTheme.TRACK);
            int filled = left + (int) Math.round((right - left) * (value - minimum) / (double) Math.max(1, maximum - minimum));
            graphics.fill(left, bottom - 2, filled, bottom, enabled ? SettingsTheme.TEXT_2 : SettingsTheme.TEXT_3);
        }

        @Override
        void click(double mouseX, int x, int width, int button) {
            drag(mouseX, x, width);
        }

        @Override
        void drag(double mouseX, int x, int width) {
            int left = x + SettingsTheme.PAD, span = Math.max(1, width - 2 * SettingsTheme.PAD);
            double fraction = Mth.clamp((mouseX - left) / span, 0, 1);
            int value = minimum + (int) Math.round(fraction * (maximum - minimum) / step) * step;
            value = Mth.clamp(value, minimum, maximum);
            if (value != get.getAsInt()) set.accept(value);
        }

        @Override
        boolean draggable() {
            return true;
        }
    }

    /** Runs something; the value column shows a short state such as a count, or a chevron when it opens a view. */
    static final class Action extends SettingsRow {
        private final Runnable run;
        private final Supplier<String> state;

        Action(Component label, String description, Runnable run, Supplier<String> state) {
            super(label, description);
            this.run = run;
            this.state = state;
        }

        @Override
        void value(GuiGraphics graphics, Font font, int x, int y, int width, int height, boolean enabled) {
            String text = state.get();
            if (text != null && !text.isEmpty()) {
                SettingsTheme.textRight(graphics, font, text, x + width - SettingsTheme.PAD, y, height, enabled ? SettingsTheme.TEXT_2 : SettingsTheme.TEXT_3);
            }
        }

        @Override
        void click(double mouseX, int x, int width, int button) {
            if (button == 0) run.run();
        }
    }
}
