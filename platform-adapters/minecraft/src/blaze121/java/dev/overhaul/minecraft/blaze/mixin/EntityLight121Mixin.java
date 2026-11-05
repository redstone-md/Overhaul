package dev.overhaul.minecraft.blaze.mixin;

import dev.overhaul.minecraft.blaze.BlazeLitEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
abstract class EntityLight121Mixin implements BlazeLitEntity {
    @Unique private long overhaul$litAt = -1;
    @Unique private int overhaul$light;

    @Override public long overhaul$litAt() { return overhaul$litAt; }
    @Override public int overhaul$light() { return overhaul$light; }
    @Override public void overhaul$lit(long tick, int packedLight) {
        overhaul$litAt = tick;
        overhaul$light = packedLight;
    }
}
