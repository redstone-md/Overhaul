package dev.overhaul.minecraft.blaze;

/** The light last sampled at an entity and the game tick it was sampled on. */
public interface BlazeLitEntity {
    long overhaul$litAt();
    int overhaul$light();
    void overhaul$lit(long tick, int packedLight);
}
