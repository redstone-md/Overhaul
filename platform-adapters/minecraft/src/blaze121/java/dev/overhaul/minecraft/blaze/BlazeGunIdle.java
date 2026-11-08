package dev.overhaul.minecraft.blaze;

/** A living entity that knows whether TACZ's gun state tick can be skipped for it. */
public interface BlazeGunIdle {
    /** True while the entity is a mob that has never held a gun, so its gun state is still at the start. */
    boolean overhaul$gunIdle();
}
