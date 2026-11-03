package dev.overhaul.core.culling;

/** Immutable frustum extracted from a column-major projection * view matrix. */
public final class Frustum {
    public enum ClipDepth { OPENGL_NEGATIVE_ONE_TO_ONE, VULKAN_ZERO_TO_ONE }
    private final double[][] planes = new double[6][4];

    public Frustum(double[] matrix, ClipDepth clipDepth) {
        if (matrix == null || matrix.length != 16 || clipDepth == null) {
            throw new IllegalArgumentException("A 4x4 matrix and depth convention are required");
        }
        for (double value : matrix) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("Matrix must be finite");
            }
        }
        extract(matrix, 0, 0, 1); extract(matrix, 1, 0, -1);
        extract(matrix, 2, 1, 1); extract(matrix, 3, 1, -1);
        if (clipDepth == ClipDepth.VULKAN_ZERO_TO_ONE) {
            for (int column = 0; column < 4; column++) planes[4][column] = matrix[column * 4 + 2];
        } else extract(matrix, 4, 2, 1);
        extract(matrix, 5, 2, -1);
    }

    private void extract(double[] matrix, int plane, int row, int sign) {
        for (int column = 0; column < 4; column++) {
            planes[plane][column] = matrix[column * 4 + 3] + sign * matrix[column * 4 + row];
        }
    }

    /** Touching/intersecting bounds remain visible. Does not allocate per query. */
    public boolean intersects(Bounds bounds) {
        if (bounds == null) throw new NullPointerException("bounds");
        for (double[] plane : planes) {
            double distance = plane[0] * bounds.positiveX(plane[0])
                    + plane[1] * bounds.positiveY(plane[1])
                    + plane[2] * bounds.positiveZ(plane[2]) + plane[3];
            if (distance < 0) return false;
        }
        return true;
    }
}
