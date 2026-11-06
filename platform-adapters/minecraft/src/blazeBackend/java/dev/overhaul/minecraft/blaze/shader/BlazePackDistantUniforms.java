package dev.overhaul.minecraft.blaze.shader;

import dev.overhaul.minecraft.blaze.BlazePackDistant;
import dev.overhaul.shader.compat.UniformBlock;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Distant Horizons' camera: the game's projection with its depth terms over DH's clip planes. */
final class BlazePackDistantUniforms {
    private final float[] projection = new float[16], inverse = new float[16], previous = new float[16];
    private final Matrix4f matrix = new Matrix4f();
    private float near, far, distance;
    private boolean initialized;

    void capture(Matrix4fc game) {
        near = BlazePackDistant.near();
        far = BlazePackDistant.far();
        distance = BlazePackDistant.renderDistance();
        game.get(projection);
        projection[10] = -(far + near) / (far - near);
        projection[14] = -2 * far * near / (far - near);
        matrix.set(projection).invert().get(inverse);
        if (!initialized) System.arraycopy(projection, 0, previous, 0, 16);
        initialized = true;
    }

    Matrix4f projection(Matrix4f into) {
        return into.set(projection);
    }

    void complete() {
        System.arraycopy(projection, 0, previous, 0, 16);
    }

    void reset() {
        initialized = false;
    }

    boolean fill(String name, UniformBlock values) {
        switch (name) {
            case "dhNearPlane" -> values.setFloat(name, near);
            case "dhFarPlane" -> values.setFloat(name, far);
            case "dhRenderDistance" -> {
                // Packs declare it as an int or a float, program by program.
                if ("int".equals(values.bindings().uniforms().get(name).type())) values.setInt(name, (int) distance); else values.setFloat(name, distance);
            }
            case "dhProjection" -> values.setMatrix(name, 4, projection);
            case "dhProjectionInverse" -> values.setMatrix(name, 4, inverse);
            case "dhPreviousProjection" -> values.setMatrix(name, 4, previous);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** What custom uniform expressions may name; a name this class does not own is NaN. */
    double read(String name, int component) {
        return switch (name) {
            case "dhNearPlane" -> near;
            case "dhFarPlane" -> far;
            case "dhRenderDistance" -> distance;
            default -> Double.NaN;
        };
    }
}
