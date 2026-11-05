package dev.overhaul.minecraft.blaze.ui;

import dev.overhaul.minecraft.blaze.BlazeFrontend;
import dev.overhaul.minecraft.blaze.mixin.OptionsSubScreen121Access;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Overhaul's settings: pages on the left, the page's rows in a column, and a side panel that explains the row under
 * the pointer. The shader pack page leaves the side clear, so the world behind it shows the pack that was just picked.
 */
public final class OverhaulSettingsScreen extends Screen {
    private static boolean vanillaOnce;
    private final Screen parent;
    private final List<SettingsPage> pages;
    private int page;
    private List<SettingsRow> rows = List.of();
    private SettingsRow focus, dragging;
    private double scroll;
    private int columnX, columnWidth, asideX, asideWidth;

    private OverhaulSettingsScreen(Screen parent) {
        super(Component.literal("Overhaul"));
        this.parent = parent;
        Minecraft game = Minecraft.getInstance();
        this.pages = List.of(new VideoPage(game, this::refresh), new OptimizationPage(this::refresh), new ShaderPackPage(game, this::refresh));
    }

    /** Called for every screen the game opens; only video settings on the owned device are replaced. */
    public static Screen replace(Screen requested) {
        if (!(requested instanceof VideoSettingsScreen video)) return requested;
        if (vanillaOnce) {
            vanillaOnce = false;
            return requested;
        }
        if (BlazeFrontend.current() == null) return requested;
        return new OverhaulSettingsScreen(((OptionsSubScreen121Access) video).overhaul$lastScreen());
    }

    @Override
    protected void init() {
        refresh();
    }

    private void refresh() {
        rows = pages.get(page).rows();
        if (focus != null && !rows.contains(focus)) focus = null;
        dragging = null;
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    private void open(int index) {
        if (index == page) return;
        page = index;
        scroll = 0;
        focus = null;
        refresh();
    }

    private void layout() {
        int free = width - SettingsTheme.SIDEBAR_WIDTH - 2 * SettingsTheme.GAP;
        columnWidth = pages.get(page).preview()
                ? Math.clamp((int) (free * 0.42), Math.min(180, free), 260)
                : Math.clamp(free - SettingsTheme.GAP - 168, Math.min(200, free), 320);
        columnX = SettingsTheme.SIDEBAR_WIDTH + SettingsTheme.GAP;
        asideX = columnX + columnWidth + SettingsTheme.GAP;
        asideWidth = width - asideX - SettingsTheme.GAP;
    }

    private int listBottom() {
        return height - SettingsTheme.GAP;
    }

    private int maxScroll() {
        int total = 0;
        for (SettingsRow row : rows) total += row.height();
        return Math.max(0, total - (listBottom() - SettingsTheme.TOP));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        if (minecraft.level == null) {
            renderPanorama(graphics, partial);
            graphics.fill(0, 0, width, height, 0x66000000);
        } else if (!pages.get(page).preview()) {
            graphics.fill(0, 0, width, height, 0x59000000);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        layout();
        renderBackground(graphics, mouseX, mouseY, partial);
        SettingsPage current = pages.get(page);
        int contentRight = current.preview() ? asideX - SettingsTheme.GAP / 2 : width;
        graphics.fill(0, 0, SettingsTheme.SIDEBAR_WIDTH, height, SettingsTheme.SIDEBAR);
        graphics.fill(SettingsTheme.SIDEBAR_WIDTH, 0, contentRight, height, SettingsTheme.CONTENT);
        graphics.fill(SettingsTheme.SIDEBAR_WIDTH - 1, 0, SettingsTheme.SIDEBAR_WIDTH, height, SettingsTheme.HAIRLINE);
        renderSidebar(graphics, mouseX, mouseY);
        graphics.drawString(font, current.title(), columnX + SettingsTheme.PAD, 12, SettingsTheme.TEXT, false);
        renderRows(graphics, mouseX, mouseY);
        if (asideWidth < 120) return;
        if (current.preview()) renderPreview(graphics, current);
        else renderDescription(graphics, current);
    }

    private void renderSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "Overhaul", 12, 12, SettingsTheme.TEXT, false);
        int x = 4, width = SettingsTheme.SIDEBAR_WIDTH - 8;
        for (int index = 0; index < pages.size(); index++) {
            int y = pageY(index);
            boolean hovered = inside(mouseX, mouseY, x, y, width, SettingsTheme.ROW);
            if (index == page) SettingsTheme.selected(graphics, x, y, width, SettingsTheme.ROW);
            else if (hovered) graphics.fill(x, y, x + width, y + SettingsTheme.ROW, SettingsTheme.HOVER);
            SettingsTheme.text(graphics, font, pages.get(index).title(), x + SettingsTheme.PAD, y, SettingsTheme.ROW, index == page ? SettingsTheme.TEXT : SettingsTheme.TEXT_2);
        }
        int vanilla = vanillaY(), done = doneY();
        if (inside(mouseX, mouseY, x, vanilla, width, SettingsTheme.ROW)) graphics.fill(x, vanilla, x + width, vanilla + SettingsTheme.ROW, SettingsTheme.HOVER);
        SettingsTheme.text(graphics, font, "Game video settings", x + SettingsTheme.PAD, vanilla, SettingsTheme.ROW, SettingsTheme.TEXT_2);
        boolean doneHover = inside(mouseX, mouseY, x, done, width, SettingsTheme.ROW);
        graphics.fill(x, done, x + width, done + SettingsTheme.ROW, doneHover ? 0xFFFFFFFF : SettingsTheme.TEXT);
        String label = "Done";
        SettingsTheme.text(graphics, font, label, x + (width - font.width(label)) / 2, done, SettingsTheme.ROW, SettingsTheme.INK);
    }

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.enableScissor(columnX, SettingsTheme.TOP, columnX + columnWidth, listBottom());
        int y = SettingsTheme.TOP - (int) scroll;
        SettingsRow hovered = rowAt(mouseX, mouseY);
        if (hovered != null) focus = hovered;
        for (SettingsRow row : rows) {
            if (y + row.height() > SettingsTheme.TOP && y < listBottom()) row.render(graphics, font, columnX, y, columnWidth, row == hovered);
            y += row.height();
        }
        graphics.disableScissor();
        int max = maxScroll();
        if (max > 0) {
            int track = listBottom() - SettingsTheme.TOP, thumb = Math.max(16, track * track / (track + max));
            int top = SettingsTheme.TOP + (int) ((track - thumb) * scroll / max), x = columnX + columnWidth + 2;
            graphics.fill(x, top, x + 2, top + thumb, SettingsTheme.HAIRLINE);
        }
    }

    private void renderDescription(GuiGraphics graphics, SettingsPage current) {
        int x = asideX + SettingsTheme.PAD, width = asideWidth - 2 * SettingsTheme.PAD, y = SettingsTheme.TOP + 6;
        if (focus != null) {
            for (FormattedCharSequence line : font.split(focus.label, width)) {
                graphics.drawString(font, line, x, y, SettingsTheme.TEXT, false);
                y += 12;
            }
            y += 4;
            y = paragraph(graphics, focus.description, x, y, width, SettingsTheme.TEXT_2, 12);
            String note = focus.note();
            if (note != null && !note.isEmpty()) paragraph(graphics, note, x, y + 8, width, SettingsTheme.TEXT_3, 12);
        }
        String status = current.status();
        if (status.isEmpty()) return;
        int bottom = listBottom() - 4;
        List<FormattedCharSequence> lines = font.split(Component.literal(status), width - 12);
        int top = bottom - lines.size() * 12;
        graphics.fill(asideX + SettingsTheme.PAD, top + 2, asideX + SettingsTheme.PAD + 4, top + 6, current.statusIsPending() ? SettingsTheme.ACCENT : SettingsTheme.TEXT_3);
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, x + 12, top, SettingsTheme.TEXT_2, false);
            top += 12;
        }
    }

    /** The world shows through the frame; a strip at its foot carries the pack's state and the row under the pointer. */
    private void renderPreview(GuiGraphics graphics, SettingsPage current) {
        int top = SettingsTheme.TOP, bottom = listBottom(), width = asideWidth - 2 * SettingsTheme.PAD;
        SettingsTheme.outline(graphics, asideX, top, asideWidth, bottom - top, SettingsTheme.HAIRLINE);
        List<FormattedCharSequence> status = font.split(Component.literal(current.status()), width);
        if (status.size() > 6) status = status.subList(0, 6);
        List<FormattedCharSequence> detail = focus == null || focus.description.isEmpty() ? List.of() : font.split(Component.literal(focus.description), width);
        if (detail.size() > 2) detail = detail.subList(0, 2);
        String note = focus == null ? null : focus.note();
        List<FormattedCharSequence> extra = note == null || note.isEmpty() ? List.of() : font.split(Component.literal(note), width);
        int strip = SettingsTheme.PAD * 2 + 12 * (status.size() + detail.size() + extra.size()) + (detail.isEmpty() && extra.isEmpty() ? 0 : 4);
        int y = bottom - 1 - strip;
        graphics.fill(asideX + 1, y, asideX + asideWidth - 1, bottom - 1, SettingsTheme.STRIP);
        y += SettingsTheme.PAD;
        int color = current.statusIsError() ? SettingsTheme.ERROR : SettingsTheme.TEXT;
        for (FormattedCharSequence line : status) {
            graphics.drawString(font, line, asideX + SettingsTheme.PAD, y, color, false);
            y += 12;
        }
        if (!detail.isEmpty() || !extra.isEmpty()) y += 4;
        for (FormattedCharSequence line : detail) {
            graphics.drawString(font, line, asideX + SettingsTheme.PAD, y, SettingsTheme.TEXT_2, false);
            y += 12;
        }
        for (FormattedCharSequence line : extra) {
            graphics.drawString(font, line, asideX + SettingsTheme.PAD, y, SettingsTheme.TEXT_3, false);
            y += 12;
        }
    }

    private int paragraph(GuiGraphics graphics, String text, int x, int y, int width, int color, int leading) {
        if (text == null || text.isEmpty()) return y;
        for (FormattedCharSequence line : font.split(Component.literal(text), width)) {
            graphics.drawString(font, line, x, y, color, false);
            y += leading;
        }
        return y;
    }

    private SettingsRow rowAt(double mouseX, double mouseY) {
        if (mouseX < columnX || mouseX >= columnX + columnWidth || mouseY < SettingsTheme.TOP || mouseY >= listBottom()) return null;
        int y = SettingsTheme.TOP - (int) scroll;
        for (SettingsRow row : rows) {
            if (mouseY >= y && mouseY < y + row.height()) return row.interactive() ? row : null;
            y += row.height();
        }
        return null;
    }

    private static int pageY(int index) {
        return SettingsTheme.TOP + index * (SettingsTheme.ROW + 2);
    }

    private int doneY() {
        return height - SettingsTheme.GAP - SettingsTheme.ROW;
    }

    private int vanillaY() {
        return doneY() - 4 - SettingsTheme.ROW;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        layout();
        int x = 4, width = SettingsTheme.SIDEBAR_WIDTH - 8;
        for (int index = 0; index < pages.size(); index++) {
            if (inside(mouseX, mouseY, x, pageY(index), width, SettingsTheme.ROW)) {
                open(index);
                return true;
            }
        }
        if (inside(mouseX, mouseY, x, vanillaY(), width, SettingsTheme.ROW)) {
            vanillaOnce = true;
            minecraft.setScreen(new VideoSettingsScreen(this, minecraft, minecraft.options));
            return true;
        }
        if (inside(mouseX, mouseY, x, doneY(), width, SettingsTheme.ROW)) {
            onClose();
            return true;
        }
        SettingsRow row = rowAt(mouseX, mouseY);
        if (row == null || !row.enabled()) return super.mouseClicked(mouseX, mouseY, button);
        row.click(mouseX, columnX, columnWidth, button);
        if (row.draggable()) dragging = row;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging == null) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        dragging.drag(mouseX, columnX, columnWidth);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.clamp(scroll - scrollY * SettingsTheme.ROW, 0, maxScroll());
        return true;
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        pages.get(page).filesDropped(files);
    }

    @Override
    public void onClose() {
        pages.forEach(SettingsPage::closed);
        minecraft.setScreen(parent);
    }
}
