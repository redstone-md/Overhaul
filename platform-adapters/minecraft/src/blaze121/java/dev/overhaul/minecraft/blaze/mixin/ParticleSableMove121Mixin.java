package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.overhaul.minecraft.blaze.BlazeOptimization;
import dev.overhaul.minecraft.blaze.BlazeParticleCollisions;
import dev.overhaul.minecraft.blaze.BlazeSableParticles;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Wraps {@code move} outside Sable's wrapper (higher priority applies later, so it lands outermost). An idle particle,
 * see {@link BlazeSableParticles#idle}, runs vanilla's move here; anything else goes on through Sable. The copy is
 * used only while the merged class shows no other code in or around the method.
 */
@Mixin(value = Particle.class, priority = 1500)
abstract class ParticleSableMove121Mixin {
    @Shadow @Final protected ClientLevel level;
    @Shadow protected boolean stoppedByCollision;
    @Shadow public boolean hasPhysics;
    @Shadow public boolean onGround;
    @Shadow protected double xd;
    @Shadow protected double zd;

    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract void setBoundingBox(AABB box);
    @Shadow protected abstract void setLocationFromBoundingbox();

    @WrapMethod(method = "move")
    private void overhaul$skipIdleSable(double x, double y, double z, Operation<Void> original) {
        if (!BlazeSableParticles.idle((Particle) (Object) this, level)) {
            original.call(x, y, z);
            return;
        }
        if (stoppedByCollision) return;
        double askedX = x, askedY = y, askedZ = z;
        if (hasPhysics && (x != 0.0 || y != 0.0 || z != 0.0) && x * x + y * y + z * z < 10000.0) {
            Vec3 asked = new Vec3(x, y, z);
            AABB box = getBoundingBox();
            Vec3 allowed = BlazeOptimization.PARTICLE_COLLISIONS.on()
                    ? BlazeParticleCollisions.collide(level, asked, box) : Entity.collideBoundingBox(null, asked, box, level, List.of());
            x = allowed.x;
            y = allowed.y;
            z = allowed.z;
        }
        if (x != 0.0 || y != 0.0 || z != 0.0) {
            setBoundingBox(getBoundingBox().move(x, y, z));
            setLocationFromBoundingbox();
        }
        if (Math.abs(askedY) >= 1.0E-5F && Math.abs(y) < 1.0E-5F) stoppedByCollision = true;
        onGround = askedY != y && askedY < 0.0;
        if (askedX != x) xd = 0.0;
        if (askedZ != z) zd = 0.0;
    }
}
