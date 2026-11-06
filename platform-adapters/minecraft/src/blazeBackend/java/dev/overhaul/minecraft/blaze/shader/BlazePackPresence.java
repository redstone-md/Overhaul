package dev.overhaul.minecraft.blaze.shader;

import dev.overhaul.shader.compat.UniformBlock;
import java.util.Arrays;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;

/** Where the camera entity stands and looks, and the lightning bolt in view, sampled once per frame. */
final class BlazePackPresence {
    private final float[] eye = new float[3], relativeEye = new float[3], look = new float[3], lightning = new float[4];
    private float velocity;

    void capture(BlazePackCapture frame) {
        var camera = frame.camera().getPosition();
        var minecraft = frame.minecraft();
        Entity entity = minecraft.getCameraEntity();
        if (entity == null) {
            store(eye, camera.x, camera.y, camera.z);
            store(relativeEye, 0, 0, 0);
            store(look, 0, 0, 0);
            velocity = 0;
        } else {
            var position = entity.getEyePosition(frame.partial());
            var view = entity.getViewVector(frame.partial());
            store(eye, position.x, position.y, position.z);
            store(relativeEye, camera.x - position.x, camera.y - position.y, camera.z - position.z);
            store(look, view.x, view.y, view.z);
            double dx = entity.getX() - entity.xOld, dy = entity.getY() - entity.yOld, dz = entity.getZ() - entity.zOld;
            velocity = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        Arrays.fill(lightning, 0);
        for (Entity candidate : minecraft.level.entitiesForRendering()) {
            if (candidate instanceof LightningBolt) {
                store(lightning, candidate.getX() - camera.x, candidate.getY() - camera.y, candidate.getZ() - camera.z);
                lightning[3] = 1;
                break;
            }
        }
    }

    boolean fill(String name, UniformBlock values) {
        switch (name) {
            case "eyePosition" -> values.setVector(name, eye);
            case "relativeEyePosition" -> values.setVector(name, relativeEye);
            case "playerLookVector" -> values.setVector(name, look);
            case "lightningBoltPosition" -> values.setVector(name, lightning);
            case "velocity" -> values.setFloat(name, velocity);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Reads what custom uniform expressions may name; a name this class does not own is NaN. */
    double read(String name, int component) {
        return switch (name) {
            case "eyePosition" -> eye[component];
            case "relativeEyePosition" -> relativeEye[component];
            case "playerLookVector" -> look[component];
            case "lightningBoltPosition" -> lightning[component];
            case "velocity" -> velocity;
            default -> Double.NaN;
        };
    }

    private static void store(float[] target, double x, double y, double z) {
        target[0] = (float) x;
        target[1] = (float) y;
        target[2] = (float) z;
    }
}
