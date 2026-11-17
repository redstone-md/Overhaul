package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Overhaul's settings: a header with the page's name and the figure that sums it up, the page's rows, a side panel
 * that explains the row under the pointer below a window onto the world, and a footer with the pages and Apply.
 * Nothing a row changes reaches the game until Apply; Reset and leaving the screen drop what was staged.
 */
public final class OverhaulSettingsScreen extends BlazeScreenBase {
    /** Starts the benchmark once the main menu first shows, then shows its result; for measuring a machine from a script. */
    private static final boolean AUTO_BENCHMARK = Boolean.getBoolean("overhaul.dev.benchmark");
    /** Some mods finish their setup on the first ticks of the menu and fail if a world opens before that. */
    private static final int AUTO_START_TICKS = 200;
    private static final int RAIL = 108, NARROW = 420, BUTTON = 18;
    private static boolean vanillaOnce, autoBenchmarked;
    private int autoStart;
    private final Screen parent;
    private final SettingsDraft draft = new SettingsDraft();
    private final BenchmarkPage benchmark;
    private final List<SettingsPage> pages;
    private int page;
    private List<SettingsRow> rows = List.of();
    private SettingsRow focus, dragging;
    private double scroll;
    /** Set by the first Back with changes staged; the second one leaves and drops them. */
    private boolean discard;
    private int railWidth, listX, listWidth, asideX, asideWidth, listTop, bodyBottom;
    private int windowX, windowY, windowWidth, windowHeight;
    private final int[] tabX, tabWidth;
    private boolean tabLabels;
    private int buttonY, backX, backWidth, applyX, applyWidth, resetX, resetWidth;

    private OverhaulSettingsScreen(Screen parent) {
        super(Component.literal("Overhaul"));
        this.parent = parent;
        Minecraft game = Minecraft.getInstance();
        GameOptions options = new GameOptions(game, draft);
        OptimizationValues optimization = new OptimizationValues(draft);
        this.benchmark = new BenchmarkPage(options, this::refresh, finished -> BenchmarkWorld.start(game, this, finished));
        this.pages = List.of(new QualityPage(options, this::refresh, benchmark::result), new DisplayPage(options, this::refresh, this::openVanilla),
                new UpscalingPage(optimization, this::refresh), new LightingPage(optimization, this::refresh), new ShaderPackPage(game, draft, this::refresh),
                new OptimizationPage(optimization, this::refresh), new OverlayPage(draft, this::refresh), benchmark, new AboutPage(this::refresh));
        this.tabX = new int[pages.size()];
        this.tabWidth = new int[pages.size()];
    }

    /** Called for every screen the game opens; only video settings on the owned device are replaced. */
    public static Screen replace(Screen requested) {
        Minecraft game = Minecraft.getInstance();
        if (BlazeFrontend.current() == null) return requested;
        if (AUTO_BENCHMARK && !autoBenchmarked && requested instanceof TitleScreen) {
            autoBenchmarked = true;
            OverhaulSettingsScreen settings = new OverhaulSettingsScreen(requested);
            settings.autoStart = AUTO_START_TICKS;
            return settings;
        }
        Screen benchmark = BenchmarkWorld.joined(game, requested);
        if (benchmark != requested) return benchmark;
        if (!BlazeUiTier.isVideo(requested)) return requested;
        if (vanillaOnce) {
            vanillaOnce = false;
            return requested;
        }
        return new OverhaulSettingsScreen(BlazeUiTier.parent(requested));
    }

    private void openVanilla() {
        vanillaOnce = true;
        Minecraft game = Minecraft.getInstance();
        game.setScreen(BlazeUiTier.vanilla(this, game));
    }

    @Override
    protected void init() {
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (autoStart > 0 && --autoStart == 0) benchmark.start();
    }

    private void refresh() {
        rows = pages.get(page).rows();
        if (focus != null && !rows.contains(focus)) focus = null;
        dragging = null;
        layout();
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private void open(int index) {
        index = Math.floorMod(index, pages.size());
        if (index == page) return;
        page = index;
        scroll = 0;
        focus = null;
        refresh();
    }

    private void apply() {
        if (draft.count() == 0) return;
        draft.apply();
        discard = false;
        refresh();
    }

    private void reset() {
        draft.reset();
        discard = false;
        refresh();
    }

    /** Leaves at once when nothing is staged; otherwise the first call warns and the second leaves. */
    private void back() {
        if (draft.count() > 0 && !discard) {
            discard = true;
            return;
        }
        draft.reset();
        minecraft.setScreen(parent);
    }

    private void layout() {
        SettingsPage current = pages.get(page);
        listTop = SettingsTheme.HEADER + 2;
        bodyBottom = height - SettingsTheme.FOOTER;
        railWidth = current.categories().isEmpty() ? 0 : Math.min(RAIL, width / 4);
        asideWidth = width < NARROW ? 0 : current.preview() ? Mth.clamp(width * 42 / 100, 150, 300) : Mth.clamp(width / 3, 130, 200);
        asideX = width - asideWidth;
        listX = railWidth;
        listWidth = asideX - listX;
        windowX = asideX + SettingsTheme.PAD;
        windowY = SettingsTheme.HEADER + SettingsTheme.PAD;
        windowWidth = asideWidth - 2 * SettingsTheme.PAD;
        windowHeight = asideWidth == 0 ? 0 : current.preview() ? (bodyBottom - windowY) * 58 / 100 : Math.min(windowWidth * 9 / 16, (bodyBottom - windowY) / 3);
        buttonY = bodyBottom + (SettingsTheme.FOOTER - BUTTON) / 2;
        String applyText = OverhaulLang.get("overhaul.ui.apply"), backText = OverhaulLang.get("overhaul.ui.back");
        applyWidth = font.width(applyText) + SettingsIcon.side(1) + 20;
        applyX = width - SettingsTheme.EDGE - applyWidth;
        backWidth = SettingsTheme.keycapWidth(font, "Esc") + font.width(backText) + 16;
        backX = applyX - 6 - backWidth;
        int start = SettingsTheme.EDGE + SettingsTheme.keycapWidth(font, "Q") + 6;
        int room = backX - 8 - SettingsTheme.keycapWidth(font, "E") - 6 - start;
        tabLabels = tabs(start, true) <= room;
        if (!tabLabels) tabs(start, false);
    }

    /** Places the tabs from {@code start}; returns how much room they take. Without labels only the open page is named. */
    private int tabs(int start, boolean labels) {
        int x = start, icon = SettingsIcon.side(1);
        for (int index = 0; index < pages.size(); index++) {
            tabX[index] = x;
            tabWidth[index] = icon + 10 + (labels || index == page ? 4 + font.width(pages.get(index).title()) : 0);
            x += tabWidth[index] + 2;
        }
        return x - 2 - start;
    }

    private int maxScroll() {
        int total = 0;
        for (SettingsRow row : rows) total += row.height();
        return Math.max(0, total + 4 - (bodyBottom - listTop));
    }

    @Override
    protected void backdrop(GuiGraphics graphics, float partial) {
        if (minecraft.level == null) noWorldBackground(graphics, partial);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        layout();
        backdrop(graphics, partial);
        SettingsPage current = pages.get(page);
        panels(graphics, current);
        header(graphics, current);
        if (railWidth > 0) rail(graphics, current, mouseX, mouseY);
        list(graphics, mouseX, mouseY);
        if (asideWidth > 0) aside(graphics, current);
        footer(graphics, mouseX, mouseY);
    }

    /** The panel covers the screen except the window in the side, where the world or the menu's background shows. */
    private void panels(GuiGraphics graphics, SettingsPage current) {
        int panel = SettingsTheme.PANEL, line = SettingsTheme.HAIRLINE;
        if (asideWidth == 0) {
            graphics.fill(0, 0, width, height, panel);
        } else {
            int right = windowX + windowWidth, bottom = windowY + windowHeight;
            graphics.fill(0, 0, width, windowY, panel);
            graphics.fill(0, windowY, windowX, bottom, panel);
            graphics.fill(right, windowY, width, bottom, panel);
            graphics.fill(0, bottom, width, height, panel);
            if (!current.preview()) graphics.fill(windowX, windowY, right, bottom, SettingsTheme.DIM);
            SettingsTheme.outline(graphics, windowX, windowY, windowWidth, windowHeight, line);
            graphics.fill(asideX, SettingsTheme.HEADER, asideX + 1, bodyBottom, line);
        }
        graphics.fill(0, SettingsTheme.HEADER - 1, width, SettingsTheme.HEADER, line);
        graphics.fill(0, bodyBottom, width, bodyBottom + 1, line);
        if (railWidth > 0) graphics.fill(railWidth - 1, SettingsTheme.HEADER, railWidth, bodyBottom, line);
    }

    private void header(GuiGraphics graphics, SettingsPage current) {
        int edge = SettingsTheme.EDGE, icon = SettingsIcon.side(2);
        current.icon().draw(graphics, edge, 3, icon, 18, SettingsTheme.ACCENT, 2);
        String value = current.readout();
        value = value == null ? "" : SettingsTheme.clip(font, value, width * 4 / 15);
        int valueWidth = large(graphics, value, width - edge - font.width(value) * 3 / 2, 19, SettingsTheme.TEXT);
        String label = current.readoutLabel();
        if (!value.isEmpty()) SettingsTheme.textRight(graphics, font, label, width - edge, 5, 10, SettingsTheme.FAINT);
        int room = width - 2 * edge - icon - 6 - Math.max(valueWidth, value.isEmpty() ? 0 : font.width(label)) - 12;
        large(graphics, SettingsTheme.clip(font, current.title(), room * 2 / 3), edge + icon + 6, 6, SettingsTheme.TEXT);
        int count = draft.count();
        String state = discard ? OverhaulLang.get("overhaul.ui.discard_hint") : count > 0 ? OverhaulLang.get("overhaul.ui.unsaved", count) : OverhaulLang.get("overhaul.ui.saved");
        graphics.fill(edge, 26, edge + 3, 29, count > 0 ? SettingsTheme.ACCENT : SettingsTheme.FAINT);
        graphics.drawString(font, state, edge + 7, 24, discard ? SettingsTheme.ACCENT : count > 0 ? SettingsTheme.TEXT : SettingsTheme.FAINT, false);
        resetWidth = 0;
        if (count == 0) return;
        String text = OverhaulLang.get("overhaul.ui.reset");
        resetX = edge + 7 + font.width(state) + 10;
        int cap = SettingsTheme.keycap(graphics, font, "R", resetX, 22, 12);
        graphics.drawString(font, text, resetX + cap + 4, 24, SettingsTheme.MUTED, false);
        resetWidth = cap + 4 + font.width(text);
    }

    /** Text at one and a half times the font's size; returns its width. */
    private int large(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(1.5f, 1.5f, 1);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
        return font.width(text) * 3 / 2;
    }

    private void rail(GuiGraphics graphics, SettingsPage current, int mouseX, int mouseY) {
        List<SettingsPage.Category> categories = current.categories();
        int row = SettingsTheme.ROW, icon = SettingsIcon.side(1), width = railWidth - 1;
        for (int index = 0; index < categories.size(); index++) {
            SettingsPage.Category category = categories.get(index);
            int y = listTop + index * row;
            if (y + row > bodyBottom) break;
            boolean chosen = index == current.category(), hovered = SettingsTheme.inside(mouseX, mouseY, 0, y, width, row);
            if (chosen || hovered) graphics.fill(0, y, width, y + row, SettingsTheme.HOVER);
            if (chosen) graphics.fill(0, y, 2, y + row, SettingsTheme.ACCENT);
            category.icon().draw(graphics, 6, y, icon, row, chosen ? SettingsTheme.ACCENT : SettingsTheme.MUTED, 1);
            int right = width - 5 - font.width(category.count());
            graphics.drawString(font, category.count(), right, y + (row - 8) / 2, SettingsTheme.FAINT, false);
            SettingsTheme.text(graphics, font, SettingsTheme.clip(font, category.name(), right - icon - 14), icon + 10, y, row, chosen ? SettingsTheme.TEXT : SettingsTheme.MUTED);
        }
    }

    private void list(GuiGraphics graphics, int mouseX, int mouseY) {
        int width = listWidth - 4;
        graphics.enableScissor(listX, listTop, listX + listWidth, bodyBottom);
        int y = listTop - (int) scroll;
        SettingsRow hovered = dragging != null ? dragging : rowAt(mouseX, mouseY);
        if (hovered != null) focus = hovered;
        for (SettingsRow row : rows) {
            if (y + row.height() > listTop && y < bodyBottom) row.render(graphics, font, listX, y, width, row == hovered, mouseX, mouseY);
            y += row.height();
        }
        graphics.disableScissor();
        int max = maxScroll();
        if (max == 0) return;
        int track = bodyBottom - listTop, thumb = Math.max(16, track * track / (track + max));
        int top = listTop + (int) ((track - thumb) * scroll / max), x = listX + listWidth - 3;
        graphics.fill(x, top, x + 2, top + thumb, SettingsTheme.FIELD_HOVER);
    }

    private void aside(GuiGraphics graphics, SettingsPage current) {
        int x = asideX + SettingsTheme.PAD, width = asideWidth - 2 * SettingsTheme.PAD, y = windowY + windowHeight + SettingsTheme.PAD;
        String status = current.status();
        List<FormattedCharSequence> lines = status == null || status.isEmpty() ? List.of() : font.split(Component.literal(status), width - 8);
        if (lines.size() > 5) lines = lines.subList(0, 5);
        int bottom = bodyBottom - SettingsTheme.PAD - lines.size() * 11;
        if (focus != null) {
            y = paragraph(graphics, focus.label, x, y, width, bottom, SettingsTheme.TEXT) + 3;
            y = paragraph(graphics, focus.description, x, y, width, bottom, SettingsTheme.MUTED);
            paragraph(graphics, focus.note(), x, y + 5, width, bottom, SettingsTheme.ACCENT);
        }
        if (lines.isEmpty()) return;
        int color = current.statusIsError() ? SettingsTheme.ERROR : current.statusIsPending() ? SettingsTheme.ACCENT : SettingsTheme.FAINT;
        graphics.fill(x, bottom + 2, x + 3, bottom + 5, color);
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, x + 8, bottom, current.statusIsError() ? SettingsTheme.ERROR : SettingsTheme.MUTED, false);
            bottom += 11;
        }
    }

    /** Wrapped text that stops above {@code bottom}; returns where the next paragraph starts. */
    private int paragraph(GuiGraphics graphics, String text, int x, int y, int width, int bottom, int color) {
        if (text == null || text.isEmpty()) return y;
        for (FormattedCharSequence line : font.split(Component.literal(text), width)) {
            if (y + 9 > bottom) break;
            graphics.drawString(font, line, x, y, color, false);
            y += 11;
        }
        return y;
    }

    private void footer(GuiGraphics graphics, int mouseX, int mouseY) {
        int top = bodyBottom + 1, tall = SettingsTheme.FOOTER - 1, icon = SettingsIcon.side(1);
        SettingsTheme.keycap(graphics, font, "Q", SettingsTheme.EDGE, top, tall);
        for (int index = 0; index < pages.size(); index++) {
            SettingsPage tab = pages.get(index);
            int x = tabX[index], width = tabWidth[index];
            boolean chosen = index == page, hovered = SettingsTheme.inside(mouseX, mouseY, x, top, width, tall);
            if (hovered && !chosen) graphics.fill(x, top, x + width, top + tall, SettingsTheme.HOVER);
            if (chosen) graphics.fill(x, height - 2, x + width, height, SettingsTheme.ACCENT);
            int color = chosen ? SettingsTheme.TEXT : hovered ? SettingsTheme.TEXT : SettingsTheme.MUTED;
            tab.icon().draw(graphics, x + 5, top, icon, tall, chosen ? SettingsTheme.ACCENT : color, 1);
            if (tabLabels || chosen) SettingsTheme.text(graphics, font, tab.title(), x + 9 + icon, top, tall, color);
        }
        int last = pages.size() - 1;
        SettingsTheme.keycap(graphics, font, "E", tabX[last] + tabWidth[last] + 6, top, tall);

        boolean backHover = SettingsTheme.inside(mouseX, mouseY, backX, buttonY, backWidth, BUTTON);
        graphics.fill(backX, buttonY, backX + backWidth, buttonY + BUTTON, backHover ? SettingsTheme.FIELD_HOVER : SettingsTheme.FIELD);
        int cap = SettingsTheme.keycap(graphics, font, "Esc", backX + 5, buttonY, BUTTON);
        SettingsTheme.text(graphics, font, OverhaulLang.get("overhaul.ui.back"), backX + 9 + cap, buttonY, BUTTON, SettingsTheme.TEXT);

        boolean dirty = draft.count() > 0, applyHover = dirty && SettingsTheme.inside(mouseX, mouseY, applyX, buttonY, applyWidth, BUTTON);
        graphics.fill(applyX, buttonY, applyX + applyWidth, buttonY + BUTTON, dirty ? applyHover ? 0xFFD8FA7A : SettingsTheme.ACCENT : SettingsTheme.FIELD);
        int ink = dirty ? SettingsTheme.INK : SettingsTheme.FAINT;
        SettingsIcon.CHECK.draw(graphics, applyX + 8, buttonY, icon, BUTTON, ink, 1);
        SettingsTheme.text(graphics, font, OverhaulLang.get("overhaul.ui.apply"), applyX + 12 + icon, buttonY, BUTTON, ink);
    }

    private SettingsRow rowAt(double mouseX, double mouseY) {
        if (mouseX < listX || mouseX >= listX + listWidth - 4 || mouseY < listTop || mouseY >= bodyBottom) return null;
        int y = listTop - (int) scroll;
        for (SettingsRow row : rows) {
            if (mouseY >= y && mouseY < y + row.height()) return row.interactive() ? row : null;
            y += row.height();
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        layout();
        if (SettingsTheme.inside(mouseX, mouseY, backX, buttonY, backWidth, BUTTON)) {
            back();
            return true;
        }
        discard = false;
        if (SettingsTheme.inside(mouseX, mouseY, applyX, buttonY, applyWidth, BUTTON)) {
            apply();
            return true;
        }
        if (resetWidth > 0 && SettingsTheme.inside(mouseX, mouseY, resetX, 20, resetWidth, 16)) {
            reset();
            return true;
        }
        for (int index = 0; index < pages.size(); index++) {
            if (SettingsTheme.inside(mouseX, mouseY, tabX[index], bodyBottom, tabWidth[index], SettingsTheme.FOOTER)) {
                open(index);
                return true;
            }
        }
        SettingsPage current = pages.get(page);
        if (railWidth > 0 && mouseX < railWidth && mouseY >= listTop && mouseY < bodyBottom) {
            int index = (int) (mouseY - listTop) / SettingsTheme.ROW;
            if (index < current.categories().size() && index != current.category()) {
                current.category(index);
                scroll = 0;
                focus = null;
                refresh();
            }
            return true;
        }
        SettingsRow row = rowAt(mouseX, mouseY);
        if (row == null || !row.enabled()) return super.mouseClicked(mouseX, mouseY, button);
        row.click(mouseX, listX, listWidth - 4, button);
        if (row.draggable() && button == 0) dragging = row;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging == null) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        dragging.drag(mouseX, listX, listWidth - 4);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        discard = false;
        switch (key) {
            case GLFW.GLFW_KEY_Q -> open(page - 1);
            case GLFW.GLFW_KEY_E -> open(page + 1);
            case GLFW.GLFW_KEY_R -> reset();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> apply();
            default -> {
                return super.keyPressed(key, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    protected boolean scrolled(double amount) {
        scroll = Mth.clamp(scroll - amount * SettingsTheme.ROW, 0, maxScroll());
        return true;
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        pages.get(page).filesDropped(files);
    }

    @Override
    public void onClose() {
        back();
    }
}
