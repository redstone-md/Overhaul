package dev.overhaul.vulkan;

import dev.overhaul.core.culling.Bounds;
import dev.overhaul.core.culling.Frustum;
import dev.overhaul.core.culling.SoftwareDepthBuffer;
import java.util.Objects;

/** Immutable per-command culling inputs. Bounds must conservatively contain every instance of a draw. */
public final class SceneVisibility {
    private final Bounds[] worldBounds;
    private final ScreenBounds[] projectedBounds;

    public SceneVisibility(Bounds[] worldBounds) { this(worldBounds, null); }

    public SceneVisibility(Bounds[] worldBounds, ScreenBounds[] projectedBounds) {
        this.worldBounds = Objects.requireNonNull(worldBounds, "worldBounds").clone();
        for (Bounds bounds : this.worldBounds) Objects.requireNonNull(bounds, "draw world bounds");
        if (projectedBounds != null && projectedBounds.length != worldBounds.length)
            throw new IllegalArgumentException("projected and world bounds must describe the same draws");
        this.projectedBounds = projectedBounds == null ? null : projectedBounds.clone();
    }

    public int drawCount() { return worldBounds.length; }

    public boolean visible(int draw, Frustum frustum, SoftwareDepthBuffer depth) {
        Objects.requireNonNull(frustum, "frustum");
        if (!frustum.intersects(worldBounds[draw])) return false;
        ScreenBounds projected = projectedBounds == null ? null : projectedBounds[draw];
        return depth == null || projected == null || !depth.occluded(projected.minX, projected.minY,
            projected.maxX, projected.maxY, projected.nearestDepth);
    }

    /**
     * Rectangle [min,max) in software-depth pixels and the nearest possible standard-Z depth.
     * Unknown projections use null. Crossing viewport/near-plane or invalid rectangles fail open.
     * Occlusion buffers must contain opaque, clipped geometry and share this projection.
     */
    public record ScreenBounds(int minX, int minY, int maxX, int maxY, float nearestDepth) { }
}
