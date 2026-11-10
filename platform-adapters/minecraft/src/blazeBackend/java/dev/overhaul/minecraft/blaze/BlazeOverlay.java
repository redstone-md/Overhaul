package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;

/**
 * A performance overlay in the manner of RivaTuner's: frame rate, frame time, lows, a frame graph, GPU time and
 * system figures in a corner of the HUD. Each frame costs one clock read into a ring; the text is built only on each
 * refresh and drawn from cache, and GPU timestamps run only while GPU time is shown. Render thread only.
 */
public final class BlazeOverlay {
    private static final BlazeOverlay INSTANCE = new BlazeOverlay();
    private static final int HISTORY = 1024, MASK = HISTORY - 1, PAD = 3, LINE = 10, GAP = 6, GRAPH_HEIGHT = 24;
    private static final int GRAPH_WIDTH = 120, LABEL = 0xA8B0B8, MUTED = 0x6E767E, GOOD = 0x7CE38B, FAIR = 0xF2D46B, POOR = 0xF0736A;
    private static final long TARGET = 1_000_000_000L / 60;
    private static final double GIB = 1024.0 * 1024 * 1024;

    private final BlazeOverlaySettings settings = BlazeOverlaySettings.get();
    private final long[] durations = new long[HISTORY], sorted = new long[HISTORY];
    private final com.sun.management.OperatingSystemMXBean system = processBean();
    private final List<String[]> lines = new ArrayList<>();
    private final List<Integer> colours = new ArrayList<>();
    private int head, count, windowFrames, revision = -1, labelWidth, valueWidth, rowWidth;
    private long lastFrame, windowNanos, refreshedAt, graphTop = TARGET * 2;
    private boolean keyHeld, gpuOpen;
    private BlazeRenderer owner;
    private BlazeFrameGpuTimer gpu;

    private BlazeOverlay() {}

    public static BlazeOverlay get() {
        return INSTANCE;
    }

    /** Closes the frame's GPU mark, before the frame's commands are submitted. */
    void beforeSubmit(BlazeRenderer renderer) {
        if (!gpuOpen || !timesGpu() || renderer != owner || gpu == null) return;
        gpuOpen = false;
        BlazeQueue.Operation end = gpu.end();
        if (end != null) renderer.enqueue(end);
    }

    /** Counts the presented frame and opens the next frame's GPU mark. */
    void presented(BlazeRenderer renderer) {
        gpuOpen = false;
        if (!settings.enabled()) {
            lastFrame = 0;
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0) {
            long duration = now - lastFrame;
            durations[head] = duration;
            head = (head + 1) & MASK;
            if (count < HISTORY) count++;
            windowFrames++;
            windowNanos += duration;
        }
        lastFrame = now;
        if (!timesGpu()) return;
        if (renderer != owner) {
            owner = renderer;
            gpu = new BlazeFrameGpuTimer(renderer.context());
            BlazeFrameGpuTimer created = gpu;
            renderer.closeWith(() -> {
                created.close();
                if (gpu == created) gpu = null;
            });
        }
        if (gpu == null) return;
        gpu.advance();
        BlazeQueue.Operation start = gpu.start();
        if (start == null) return;
        renderer.enqueue(start);
        gpuOpen = true;
    }

    private boolean timesGpu() {
        return settings.enabled() && settings.shows(BlazeOverlayItem.GPU_TIME);
    }

    /** Draws the overlay over the HUD and watches its hotkey. */
    public void render(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        watchKey(client);
        if (!settings.enabled() || settings.hideWithHud() && client.options.hideGui) return;
        long now = System.nanoTime();
        if (revision != settings.revision() || now - refreshedAt >= settings.refreshMillis() * 1_000_000L) refresh(client, now);
        if (lines.isEmpty() && !settings.shows(BlazeOverlayItem.GRAPH)) return;
        draw(graphics, client.font);
    }

    private void watchKey(Minecraft client) {
        int key = settings.hotkey();
        if (key == 0) return;
        boolean down = InputConstants.isKeyDown(client.getWindow().getWindow(), key);
        if (down && !keyHeld && client.screen == null) {
            settings.enabled(!settings.enabled());
            settings.save();
        }
        keyHeld = down;
    }

    private void refresh(Minecraft client, long now) {
        revision = settings.revision();
        refreshedAt = now;
        lines.clear();
        colours.clear();
        double fps = windowNanos > 0 ? windowFrames * 1e9 / windowNanos : 0;
        double frameMillis = windowFrames > 0 ? windowNanos / 1e6 / windowFrames : 0;
        windowFrames = 0;
        windowNanos = 0;
        long worst = percentile(0.99), worstest = percentile(0.999);
        graphTop = Math.max(TARGET * 2, (long) (worstest * 1.15));
        int tint = settings.tint().rgb;
        BlazeRenderer renderer = BlazeFrontend.current();
        for (BlazeOverlayItem item : BlazeOverlayItem.values()) {
            if (!settings.shows(item)) continue;
            switch (item) {
                case FPS -> add("FPS", String.valueOf(Math.round(fps)), speed(fps, tint));
                case FRAME_TIME -> add("Frame", String.format(Locale.ROOT, "%.1f ms", frameMillis), tint);
                case LOWS -> add("1% / 0.1%", worst == 0 ? "-" : String.format(Locale.ROOT, "%d / %d", Math.round(1e9 / worst), Math.round(1e9 / worstest)), tint);
                case GPU_TIME -> add("GPU", gpu == null || gpu.millis() < 0 ? "-" : String.format(Locale.ROOT, "%.1f ms", gpu.millis()), tint);
                case CPU -> add("CPU", system == null ? "-" : Math.round(Math.max(0, system.getProcessCpuLoad()) * 100) + "%", tint);
                case MEMORY -> {
                    Runtime runtime = Runtime.getRuntime();
                    add("RAM", gigabytes(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()), tint);
                }
                case VRAM -> {
                    long[] memory = renderer == null ? null : renderer.allocator().deviceMemory();
                    add("VRAM", memory == null ? "-" : gigabytes(memory[0], memory[1]), tint);
                }
                case RENDER -> add("Res", resolution(client), tint);
                case DEVICE -> add("Card", renderer == null ? "-" : renderer.context().deviceName(), tint);
                case CHUNKS -> add("Chunks", client.levelRenderer == null ? "-" : BlazeOverlayTier.renderedSections(client.levelRenderer) + " drawn", tint);
                case ENTITIES -> add("Entities", client.levelRenderer == null ? "-" : entities(client), tint);
                case POSITION -> add("XYZ", position(client.getCameraEntity()), tint);
                case GRAPH -> {}
            }
        }
        Font font = client.font;
        labelWidth = valueWidth = rowWidth = 0;
        for (String[] line : lines) {
            labelWidth = Math.max(labelWidth, font.width(line[0]));
            valueWidth = Math.max(valueWidth, font.width(line[1]));
            rowWidth += font.width(line[0]) + 4 + font.width(line[1]) + GAP * 2;
        }
        if (!lines.isEmpty()) rowWidth -= GAP * 2;
    }

    private void add(String label, String value, int colour) {
        lines.add(new String[]{label, value});
        colours.add(colour);
    }

    private int speed(double fps, int tint) {
        if (!settings.speedColours()) return tint;
        return fps >= 60 ? GOOD : fps >= 30 ? FAIR : POOR;
    }

    private long percentile(double fraction) {
        if (count < 10) return 0;
        System.arraycopy(durations, 0, sorted, 0, count);
        Arrays.sort(sorted, 0, count);
        return sorted[Math.min(count - 1, (int) Math.floor(count * fraction))];
    }

    private static String gigabytes(long used, long total) {
        return String.format(Locale.ROOT, "%.1f / %.1f GB", used / GIB, total / GIB);
    }

    private static String resolution(Minecraft client) {
        var main = client.getMainRenderTarget();
        String upscaled = BlazeUpscale.get().describe();
        return upscaled != null ? upscaled : main.width + "x" + main.height;
    }

    /** Drawn and total, from the game's own "E: drawn/total, ..." line. */
    private static String entities(Minecraft client) {
        String statistics = client.levelRenderer.getEntityStatistics();
        int comma = statistics.indexOf(',');
        String counts = statistics.substring(statistics.startsWith("E: ") ? 3 : 0, comma > 0 ? comma : statistics.length());
        return counts.replace("/", " / ");
    }

    private static String position(Entity camera) {
        if (camera == null) return "-";
        return camera.getBlockX() + " " + camera.getBlockY() + " " + camera.getBlockZ() + " " + camera.getDirection().getName();
    }

    private void draw(GuiGraphics graphics, Font font) {
        float scale = settings.scale() / 100f;
        boolean row = settings.layout() == BlazeOverlaySettings.Layout.ROW, graph = settings.shows(BlazeOverlayItem.GRAPH);
        int textWidth = row ? rowWidth : labelWidth + GAP + valueWidth;
        int textHeight = lines.isEmpty() ? 0 : row ? LINE : lines.size() * LINE;
        int width = Math.max(textWidth, graph ? GRAPH_WIDTH : 0) + PAD * 2;
        int height = textHeight + (graph ? GRAPH_HEIGHT + (textHeight > 0 ? 2 : 0) : 0) + PAD * 2 - 1;
        int screenWidth = (int) (graphics.guiWidth() / scale), screenHeight = (int) (graphics.guiHeight() / scale);
        BlazeOverlaySettings.Corner corner = settings.corner();
        int x = corner.right() ? screenWidth - width - 2 : 2, y = corner.bottom() ? screenHeight - height - 2 : 2;
        var pose = graphics.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1);
        int alpha = Math.round(settings.opacity() * 2.55f);
        if (alpha > 0) graphics.fill(x, y, x + width, y + height, alpha << 24 | 0x0E1012);
        boolean shadow = settings.opacity() < 30;
        int textX = x + PAD, textY = y + PAD;
        for (int i = 0; i < lines.size(); i++) {
            String[] line = lines.get(i);
            int colour = 0xFF000000 | colours.get(i);
            if (row) {
                graphics.drawString(font, line[0], textX, textY, 0xFF000000 | LABEL, shadow);
                textX += font.width(line[0]) + 4;
                graphics.drawString(font, line[1], textX, textY, colour, shadow);
                textX += font.width(line[1]) + GAP * 2;
            } else {
                graphics.drawString(font, line[0], textX, textY + i * LINE, 0xFF000000 | LABEL, shadow);
                graphics.drawString(font, line[1], x + PAD + labelWidth + GAP, textY + i * LINE, colour, shadow);
            }
        }
        if (graph) drawGraph(graphics, x + PAD, y + height - PAD, width - PAD * 2);
        pose.popPose();
    }

    /** One bar per recent frame, newest on the right, against a line at 60 fps. */
    private void drawGraph(GuiGraphics graphics, int left, int bottom, int width) {
        int bars = Math.min(width, count);
        int tint = 0xFF000000 | settings.tint().rgb;
        for (int i = 0; i < bars; i++) {
            long duration = durations[(head - bars + i) & MASK];
            int bar = (int) Math.max(1, Math.min(GRAPH_HEIGHT, duration * GRAPH_HEIGHT / graphTop));
            int colour = settings.speedColours() && duration > TARGET * 2 ? 0xFF000000 | POOR : tint;
            int at = left + width - bars + i;
            graphics.fill(at, bottom - bar, at + 1, bottom, colour);
        }
        int line = bottom - (int) (TARGET * GRAPH_HEIGHT / graphTop);
        graphics.fill(left, line, left + width, line + 1, 0x90000000 | MUTED);
    }

    private static com.sun.management.OperatingSystemMXBean processBean() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
    }
}
