package dev.overhaul.core.culling;

import java.util.Arrays;

/**
 * Single-thread-owned, standard-Z software occlusion buffer. Input triangles must
 * already be clipped to the viewport and near plane. Only opaque occluders belong
 * here. Uncovered samples stay at far depth; partial coverage never hides a tile.
 */
public final class SoftwareDepthBuffer {
    private static final float DEPTH_BIAS = 0.00001f;
    private final int width, height;
    private final int[] widths, heights;
    private final float[][] levels;
    private boolean dirty;

    public SoftwareDepthBuffer(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > 16_777_216) {
            throw new IllegalArgumentException("Invalid software depth dimensions");
        }
        this.width = width; this.height = height;
        int count = 1, w = width, h = height;
        while (w > 1 || h > 1) { w = (w + 1) / 2; h = (h + 1) / 2; count++; }
        widths = new int[count]; heights = new int[count]; levels = new float[count][];
        w = width; h = height;
        for (int level = 0; level < count; level++) {
            widths[level] = w; heights[level] = h; levels[level] = new float[w * h];
            w = (w + 1) / 2; h = (h + 1) / 2;
        }
        clear();
    }

    public void clear() {
        Arrays.fill(levels[0], 1f);
        dirty = true;
    }

    /** Rasterizes strictly interior sample centers, leaving shared edges conservative. */
    public void rasterize(float ax, float ay, float az, float bx, float by, float bz,
                          float cx, float cy, float cz) {
        validate(ax, ay, az); validate(bx, by, bz); validate(cx, cy, cz);
        double area = edge(ax, ay, bx, by, cx, cy);
        if (Math.abs(area) < 1e-12) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5, py = y + 0.5;
                double a = edge(bx, by, cx, cy, px, py) / area;
                double b = edge(cx, cy, ax, ay, px, py) / area;
                double c = 1 - a - b;
                if (a <= 1e-8 || b <= 1e-8 || c <= 1e-8) continue;
                float depth = (float) (a * az + b * bz + c * cz);
                int index = y * width + x;
                levels[0][index] = Math.min(levels[0][index], depth);
            }
        }
        dirty = true;
    }

    private void validate(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || x < 0 || y < 0 || x > width || y > height || z < 0 || z > 1) {
            throw new IllegalArgumentException("Triangles must be clipped; depth must be in [0,1]");
        }
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /**
     * Tests a conservative projected rectangle [min,max), and its NEAREST depth.
     * Bounds crossing the viewport/near plane must remain visible until clipped.
     */
    public boolean occluded(int minX, int minY, int maxX, int maxY, float nearestDepth) {
        if (minX < 0 || minY < 0 || maxX > width || maxY > height
                || minX >= maxX || minY >= maxY || !Float.isFinite(nearestDepth)
                || nearestDepth < 0 || nearestDepth > 1) return false;
        if (dirty) rebuild();
        return test(levels.length - 1, 0, 0, minX, minY, maxX, maxY, nearestDepth);
    }

    private void rebuild() {
        for (int level = 1; level < levels.length; level++) {
            int childWidth = widths[level - 1], childHeight = heights[level - 1];
            for (int y = 0; y < heights[level]; y++) {
                for (int x = 0; x < widths[level]; x++) {
                    float maximum = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int cx = 2 * x + dx, cy = 2 * y + dy;
                            if (cx < childWidth && cy < childHeight) {
                                maximum = Math.max(maximum, levels[level - 1][cy * childWidth + cx]);
                            }
                        }
                    }
                    levels[level][y * widths[level] + x] = maximum;
                }
            }
        }
        dirty = false;
    }

    private boolean test(int level, int x, int y, int minX, int minY, int maxX, int maxY, float near) {
        int scale = 1 << level, left = x * scale, top = y * scale;
        int right = Math.min(width, left + scale), bottom = Math.min(height, top + scale);
        if (right <= minX || bottom <= minY || left >= maxX || top >= maxY) return true;
        if (near > levels[level][y * widths[level] + x] + DEPTH_BIAS) return true;
        if (level == 0 || (left >= minX && top >= minY && right <= maxX && bottom <= maxY)) return false;
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int cx = 2 * x + dx, cy = 2 * y + dy;
                if (cx < widths[level - 1] && cy < heights[level - 1]
                        && !test(level - 1, cx, cy, minX, minY, maxX, maxY, near)) return false;
            }
        }
        return true;
    }
}
