package dev.overhaul.minecraft.blaze;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * The three axis normals of a pose, transformed and packed. Every cube of a model part shares its part's pose, so the
 * render thread keeps the last answer and reuses it while the normal matrix holds the same values.
 */
final class BlazeCubeAxes {
    private static final BlazeCubeAxes RENDER = new BlazeCubeAxes();
    private final Vector3f scratch = new Vector3f();
    private boolean known;
    private float m00, m01, m02, m10, m11, m12, m20, m21, m22;
    private int x, y, z;

    private BlazeCubeAxes() {}

    static BlazeCubeAxes of(PoseStack.Pose pose) {
        BlazeCubeAxes axes = RenderSystem.isOnRenderThread() ? RENDER : new BlazeCubeAxes();
        axes.update(pose);
        return axes;
    }

    int x() { return x; }
    int y() { return y; }
    int z() { return z; }

    /** The packed normal of a face pointing along this direction. */
    int toward(Direction direction) {
        return switch (direction) {
            case EAST -> x;
            case UP -> y;
            case SOUTH -> z;
            case WEST -> opposite(x);
            case DOWN -> opposite(y);
            case NORTH -> opposite(z);
        };
    }

    static int opposite(int packed) {
        return -(byte) packed & 0xFF | (-(byte) (packed >> 8) & 0xFF) << 8 | (-(byte) (packed >> 16) & 0xFF) << 16;
    }

    private void update(PoseStack.Pose pose) {
        Matrix3f n = pose.normal();
        if (known && n.m00 == m00 && n.m01 == m01 && n.m02 == m02 && n.m10 == m10 && n.m11 == m11 && n.m12 == m12
                && n.m20 == m20 && n.m21 == m21 && n.m22 == m22) return;
        m00 = n.m00; m01 = n.m01; m02 = n.m02;
        m10 = n.m10; m11 = n.m11; m12 = n.m12;
        m20 = n.m20; m21 = n.m21; m22 = n.m22;
        x = BlazeCubeMesh.packed(pose, 1f, 0f, 0f, scratch);
        y = BlazeCubeMesh.packed(pose, 0f, 1f, 0f, scratch);
        z = BlazeCubeMesh.packed(pose, 0f, 0f, 1f, scratch);
        known = true;
    }
}
