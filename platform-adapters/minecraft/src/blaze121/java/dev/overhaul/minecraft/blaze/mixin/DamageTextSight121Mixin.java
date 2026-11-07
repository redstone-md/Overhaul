package dev.overhaul.minecraft.blaze.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Arrays;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * JDamageIndicators casts a ray through the blocks from the player's eyes to every floating damage number, every frame.
 * A number stays where it spawned, so the answer for its spot is kept for the client tick and asked again only in the
 * next one: an occluded number shows or hides at most one tick late. Numbers are few, so the spots sit in a flat array.
 */
@Pseudo
@Mixin(targets = "dev.jeremyseq.damage_text.DamageTextRenderer", remap = false)
abstract class DamageTextSight121Mixin {
    /** x, y, z and the answer (1 or 0) of each spot asked this tick. */
    @Unique private static float[] overhaul$spots = new float[64];
    @Unique private static int overhaul$used;
    @Unique private static long overhaul$tick = Long.MIN_VALUE;

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Ldev/jeremyseq/damage_text/DamageTextRenderer;hasLineOfSight(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;FFF)Z"),
            require = 0)
    private static boolean overhaul$tickSight(LivingEntity viewer, Level level, float x, float y, float z, Operation<Boolean> ray) {
        long now = level.getGameTime();
        if (now != overhaul$tick) {
            overhaul$used = 0;
            overhaul$tick = now;
        }
        float[] spots = overhaul$spots;
        for (int i = 0; i < overhaul$used; i += 4) {
            if (spots[i] == x && spots[i + 1] == y && spots[i + 2] == z) return spots[i + 3] != 0;
        }
        boolean seen = ray.call(viewer, level, x, y, z);
        if (overhaul$used == spots.length) overhaul$spots = spots = Arrays.copyOf(spots, spots.length * 2);
        spots[overhaul$used] = x;
        spots[overhaul$used + 1] = y;
        spots[overhaul$used + 2] = z;
        spots[overhaul$used + 3] = seen ? 1 : 0;
        overhaul$used += 4;
        return seen;
    }
}
