package dev.overhaul.minecraft.blaze;

import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;

/**
 * Culling a particle against the view reads the particle itself, and with thousands of them scattered over the heap
 * each read is a cache miss, paid on every frame even for the particles that end up hidden. Particles only move,
 * appear and die during the particle tick, so right after it this keeps a packed copy of every plain particle's box:
 * the frame culls from the copy and touches only the particles it draws.
 *
 * <p>The frame walks each queue in the order the tick left it, so a particle is matched against the copy by reference
 * at a running cursor, falling back to the start of each queue; anything else takes the game's path. Only the render
 * thread, which also runs the particle tick, touches this.
 */
public final class BlazeParticleBounds {
    /** Read on every particle of every frame; a plain field so a measurement can flip it in a running game. */
    public static boolean on = BlazeOptimization.PARTICLE_BOUNDS.on() && BlazeOptimization.PARTICLE_CULLING.on();
    private static final ClassValue<Boolean> PLAIN_BOX = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getRenderBoundingBox", float.class).getDeclaringClass() == SingleQuadParticle.class;
            } catch (NoSuchMethodException missing) {
                return false;
            }
        }
    };

    /** Whether each draw first tests every packed box in one pass; flipped by a measurement like {@link #on}. */
    public static boolean pass = BlazeOptimization.PARTICLE_CULL_PASS.on();
    /** {@link #visibility} answers: hidden, shown, or not tested (no plain box, or no pass ran). */
    public static final byte HIDDEN = 0, SHOWN = 1, UNTESTED = 2;

    private static Particle[] particles = new Particle[0];
    private static float[] boxes = new float[0];
    private static byte[] visible = new byte[0];
    private static int[] starts = new int[0];
    private static int count, queues, cursor;
    private static double anchorX, anchorY, anchorZ;

    private BlazeParticleBounds() {}

    /** Whether the particle's class keeps SingleQuadParticle's render box, so its box can be tested without building it. */
    public static boolean plainBox(Particle particle) {
        return PLAIN_BOX.get(particle.getClass());
    }

    /** Copies every particle's box after the tick; drops the copy when switched off. */
    public static void capture(Map<ParticleRenderType, Queue<Particle>> byType) {
        if (!on) {
            clear();
            return;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 anchor = camera.getPosition();
        anchorX = anchor.x;
        anchorY = anchor.y;
        anchorZ = anchor.z;
        int total = 0;
        queues = 0;
        for (Queue<Particle> queue : byType.values()) {
            if (queue.isEmpty()) continue;
            reserve(total + queue.size(), queues + 1);
            starts[queues++] = total;
            for (Particle particle : queue) {
                particles[total] = particle;
                int at = total << 2;
                if (particle instanceof BlazeParticleBox box && plainBox(particle)) {
                    box.overhaul$bounds(boxes, at, anchorX, anchorY, anchorZ);
                } else {
                    boxes[at + 3] = Float.NaN;
                }
                total++;
            }
        }
        if (total < count) Arrays.fill(particles, total, count, null);
        count = total;
        cursor = 0;
        Arrays.fill(visible, 0, Math.min(visible.length, total), UNTESTED);
    }

    /** Tests every plain box against the view once, for the draw that is about to read {@link #visibility}. */
    public static void cull(FrustumIntersection view, double camX, double camY, double camZ) {
        if (!on || !pass) return;
        if (visible.length < count) visible = new byte[particles.length];
        float dx = (float) (camX - anchorX), dy = (float) (camY - anchorY), dz = (float) (camZ - anchorZ);
        float[] boxes = BlazeParticleBounds.boxes;
        byte[] visible = BlazeParticleBounds.visible;
        for (int index = 0, at = 0; index < count; index++, at += 4) {
            float half = boxes[at + 3];
            if (half != half) {
                visible[index] = UNTESTED;
                continue;
            }
            float x = boxes[at] - dx, y = boxes[at + 1] - dy, z = boxes[at + 2] - dz;
            visible[index] = view.testAab(x - half, y - half, z - half, x + half, y + half, z + half) ? SHOWN : HIDDEN;
        }
    }

    /** The last {@link #cull} answer for the box at {@code at} from {@link #find}. */
    public static byte visibility(int at) {
        int index = at >> 2;
        return index < visible.length ? visible[index] : UNTESTED;
    }

    /** Forgets every particle, so none outlives the level it came from. */
    public static void clear() {
        if (count > 0) Arrays.fill(particles, 0, count, null);
        count = 0;
        queues = 0;
        cursor = 0;
    }

    /** Where the particle's box starts in {@link #boxes()}, or -1 when the copy does not hold a plain box for it. */
    public static int find(Particle particle) {
        int index = cursor;
        if (index >= count || particles[index] != particle) {
            index = -1;
            for (int queue = 0; queue < queues; queue++) {
                if (particles[starts[queue]] == particle) {
                    index = starts[queue];
                    break;
                }
            }
            if (index < 0) return -1;
        }
        cursor = index + 1;
        int at = index << 2;
        return Float.isNaN(boxes[at + 3]) ? -1 : at;
    }

    /** Centre x, y and z relative to the anchor, then half size, four floats per particle. */
    public static float[] boxes() {
        return boxes;
    }

    public static double anchorX() {
        return anchorX;
    }

    public static double anchorY() {
        return anchorY;
    }

    public static double anchorZ() {
        return anchorZ;
    }

    private static void reserve(int size, int queueCount) {
        if (size > particles.length) {
            int grown = Math.max(size, particles.length + (particles.length >> 1) + 256);
            particles = Arrays.copyOf(particles, grown);
            boxes = Arrays.copyOf(boxes, grown << 2);
        }
        if (queueCount > starts.length) starts = Arrays.copyOf(starts, queueCount + 8);
    }
}
