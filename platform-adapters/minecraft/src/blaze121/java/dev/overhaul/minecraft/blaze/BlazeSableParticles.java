package dev.overhaul.minecraft.blaze;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.slf4j.LoggerFactory;

/**
 * Sable wraps every particle move to push it with the moving sub-levels it touches, allocating a set, a box and six
 * vectors each time. A particle that rides no sub-level and carries no velocity inherited from one, in a level with no
 * sub-level loaded, moves exactly as vanilla does, so that case skips the wrapper. Sable is reached by reflection:
 * the build does not depend on it.
 */
public final class BlazeSableParticles {
    private static final MethodHandle TRACKING, INHERITED;
    private static final boolean READY;

    static {
        MethodHandle tracking = null, inherited = null;
        boolean ready = false;
        try {
            Field tracked = Particle.class.getDeclaredField("sable$trackingSubLevel");
            Field velocity = Particle.class.getDeclaredField("sable$inheritedVelocity");
            tracked.setAccessible(true);
            velocity.setAccessible(true);
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            tracking = lookup.unreflectGetter(tracked).asType(MethodType.methodType(Object.class, Particle.class));
            inherited = lookup.unreflectGetter(velocity).asType(MethodType.methodType(Vector3d.class, Particle.class));
            ready = BlazeSableLevels.ready() && ParticleMoveCheck.clean();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LoggerFactory.getLogger(BlazeSableParticles.class).info("Overhaul sableParticles idle path off: {}", failure.toString());
        }
        TRACKING = tracking;
        INHERITED = inherited;
        READY = ready;
    }

    private BlazeSableParticles() {}

    /** Whether Sable's move wrapper would leave this particle's move exactly vanilla. */
    public static boolean idle(Particle particle, Level level) {
        if (!READY) return false;
        try {
            if ((Object) TRACKING.invokeExact(particle) != null) return false;
            Vector3d velocity = (Vector3d) INHERITED.invokeExact(particle);
            if (velocity.x != 0.0 || velocity.y != 0.0 || velocity.z != 0.0) return false;
        } catch (Throwable failure) {
            return false;
        }
        return BlazeSableLevels.empty(level);
    }
}
