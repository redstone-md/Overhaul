package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Every particle looks its light up through the chunk and section maps every frame, yet it reads its tick position,
 * which only the client tick moves. The answer is kept for the tick, so light changes reach a particle at most one
 * tick late. The wrapper sits around the whole base method, Sable's sub-level light check included; subclasses that
 * blend their own glow over it per frame still do so.
 */
@Mixin(value = Particle.class, priority = 1500)
abstract class ParticleLight121Mixin {
    @Shadow @Final protected ClientLevel level;
    @Unique private long overhaul$litAt = Long.MIN_VALUE;
    @Unique private int overhaul$light;

    @WrapMethod(method = "getLightColor")
    private int overhaul$oncePerTick(float partialTick, Operation<Integer> lookup) {
        long tick = level.getGameTime();
        if (overhaul$litAt == tick) return overhaul$light;
        int light = lookup.call(partialTick);
        overhaul$litAt = tick;
        overhaul$light = light;
        return light;
    }
}
