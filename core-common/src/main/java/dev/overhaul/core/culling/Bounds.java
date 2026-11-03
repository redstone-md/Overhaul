package dev.overhaul.core.culling;

/** Immutable world-space bounds; callers provide bounds conservative for animation. */
public final class Bounds {
    private final double minX, minY, minZ, maxX, maxY, maxZ;

    public Bounds(double minX, double minY, double minZ,
                  double maxX, double maxY, double maxZ) {
        if (!finite(minX) || !finite(minY) || !finite(minZ)
                || !finite(maxX) || !finite(maxY) || !finite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Bounds must be finite and ordered");
        }
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    double positiveX(double normal) { return normal >= 0 ? maxX : minX; }
    double positiveY(double normal) { return normal >= 0 ? maxY : minY; }
    double positiveZ(double normal) { return normal >= 0 ? maxZ : minZ; }
}
