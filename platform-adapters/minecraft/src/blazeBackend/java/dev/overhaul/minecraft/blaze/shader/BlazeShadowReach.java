package dev.overhaul.minecraft.blaze.shader;

import org.joml.Matrix4fc;

/**
 * Whether a box's shadow can land inside the player's view. The box is swept along the light for the depth of the light's
 * frustum and tested against the side planes of the player's frustum: a box the sweep leaves wholly outside one plane
 * casts only onto ground the player cannot see. Near and far are left out, so shadows on Distant Horizons terrain past
 * the game's far plane and around the camera itself are kept. Camera-relative coordinates throughout.
 */
final class BlazeShadowReach {
    /** Left, right, bottom and top, each as a normal and a distance; unnormalised, since only signs are read. */
    private final float[] planes = new float[16];
    /** Per plane, how far the sweep pushes a box along the plane's normal; zero where the sweep moves away from it. */
    private final float[] pushes = new float[4];

    /**
     * @param viewProjection the player's projection times its camera-relative view
     * @param lightX the direction light travels, any length
     * @param length how far along that direction a shadow can fall, in units of the direction's length
     */
    void set(Matrix4fc viewProjection, float lightX, float lightY, float lightZ, float length) {
        Matrix4fc m = viewProjection;
        plane(0, m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
        plane(1, m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
        plane(2, m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21(), m.m33() + m.m31());
        plane(3, m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
        for (int plane = 0; plane < 4; plane++) {
            int at = plane * 4;
            pushes[plane] = Math.max(0, length * (planes[at] * lightX + planes[at + 1] * lightY + planes[at + 2] * lightZ));
        }
    }

    private void plane(int index, float x, float y, float z, float w) {
        int at = index * 4;
        planes[at] = x;
        planes[at + 1] = y;
        planes[at + 2] = z;
        planes[at + 3] = w;
    }

    /** True unless the swept box lies wholly outside one side plane. */
    boolean reaches(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int plane = 0; plane < 4; plane++) {
            int at = plane * 4;
            float x = planes[at], y = planes[at + 1], z = planes[at + 2];
            float farthest = x * (x > 0 ? maxX : minX) + y * (y > 0 ? maxY : minY) + z * (z > 0 ? maxZ : minZ) + planes[at + 3];
            if (farthest + pushes[plane] < 0) return false;
        }
        return true;
    }
}
