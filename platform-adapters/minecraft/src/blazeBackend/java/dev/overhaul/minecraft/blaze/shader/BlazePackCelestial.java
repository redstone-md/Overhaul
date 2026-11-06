package dev.overhaul.minecraft.blaze.shader;

import dev.overhaul.shader.compat.UniformBlock;
import dev.overhaul.shader.compat.UnsupportedShaderException;
import dev.overhaul.shader.pack.PackShadowSettings;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** One player-relative light camera and the sun and moon, as shadow draws and their consumers read them. */
final class BlazePackCelestial {
    private final Matrix4f matrix = new Matrix4f();
    private final float[] projection = new float[16], projectionInverse = new float[16], view = new float[16], viewInverse = new float[16];
    private final Vector3f sun = new Vector3f(), moon = new Vector3f(), up = new Vector3f();
    private float sunAngle, shadowAngle;
    private boolean day;

    void capture(BlazePackCapture frame, PackShadowSettings settings) {
        float sunDegrees = frame.minecraft().level.getTimeOfDay(frame.partial()) * 360;
        float moonDegrees = (sunDegrees + 180) % 360;
        sunAngle = angle(sunDegrees);
        day = sunAngle < 0.5f;
        shadowAngle = day ? sunAngle : angle(moonDegrees);
        var position = frame.camera().getPosition();
        float skyAngle = shadowAngle < 0.25f ? shadowAngle + 0.75f : shadowAngle - 0.25f;
        matrix.rotationX(radians(90)).rotateZ(radians(-360 * skyAngle)).rotateX(radians(settings.sunPathRotation()));
        float interval = settings.interval();
        if (interval != 0) {
            float half = interval / 2;
            matrix.translate((float) position.x % interval - half, (float) position.y % interval - half, (float) position.z % interval - half);
        }
        matrix.get(view);
        matrix.invert().get(viewInverse);
        if (settings.fov() != null) {
            // Keeps the Iris legacy matrix, including its unusual m33=1.
            float near = -100.05f, far = 156f, y = (float) (1.0f / Math.tan(Math.toRadians(settings.fov()) * 0.5f));
            matrix.set(y, 0, 0, 0, 0, y, 0, 0, 0, 0, (far + near) / (near - far), -1, 0, 0, 2 * far * near / (near - far), 1).get(projection);
        } else {
            float distance = frame.minecraft().options.getEffectiveRenderDistance() * 16f;
            float near = settings.near() == -1 ? -distance : settings.near(), far = settings.far() == -1 ? distance : settings.far();
            if (!(far > near)) throw new UnsupportedShaderException("Resolved shadow clip range must have far > near");
            matrix.setOrthoSymmetric(settings.distance() * 2, settings.distance() * 2, near, far, false).get(projection);
        }
        matrix.invert().get(projectionInverse);
        celestial(frame.view(), settings.sunPathRotation(), sunDegrees, sun);
        celestial(frame.view(), settings.sunPathRotation(), moonDegrees, moon);
        matrix.set(frame.view()).rotateY(radians(-90)).transformDirection(0, 100, 0, up);
    }

    Matrix4f shadowView(Matrix4f into) {
        return into.set(view);
    }

    Matrix4f shadowProjection(Matrix4f into) {
        return into.set(projection);
    }

    boolean fill(String name, UniformBlock values) {
        switch (name) {
            case "shadowProjection" -> values.setMatrix(name, 4, projection);
            case "shadowProjectionInverse" -> values.setMatrix(name, 4, projectionInverse);
            case "shadowModelView" -> values.setMatrix(name, 4, view);
            case "shadowModelViewInverse" -> values.setMatrix(name, 4, viewInverse);
            case "sunAngle" -> values.setFloat(name, sunAngle);
            case "shadowAngle" -> values.setFloat(name, shadowAngle);
            case "sunPosition" -> vector(values, name, sun);
            case "moonPosition" -> vector(values, name, moon);
            case "shadowLightPosition" -> vector(values, name, day ? sun : moon);
            case "upPosition" -> vector(values, name, up);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Reads a non-matrix value {@link #fill} owns for custom uniform expressions; NaN marks any other name. */
    double read(String name, int component) {
        return switch (name) {
            case "sunAngle" -> sunAngle;
            case "shadowAngle" -> shadowAngle;
            case "sunPosition" -> sun.get(component);
            case "moonPosition" -> moon.get(component);
            case "shadowLightPosition" -> (day ? sun : moon).get(component);
            case "upPosition" -> up.get(component);
            default -> Double.NaN;
        };
    }

    private void celestial(Matrix4fc camera, float path, float degrees, Vector3f result) {
        matrix.set(camera).rotateY(radians(-90)).rotateZ(radians(path)).rotateX(radians(degrees)).transformPosition(0, 100, 0, result);
    }

    private static void vector(UniformBlock values, String name, Vector3f vector) {
        values.setVector(name, vector.x, vector.y, vector.z);
    }

    private static float angle(float degrees) {
        float result = degrees + 90;
        return (result > 360 ? result - 360 : result) / 360;
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
