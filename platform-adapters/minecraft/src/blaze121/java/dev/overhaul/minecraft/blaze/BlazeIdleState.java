package dev.overhaul.minecraft.blaze;

/** Mod state that can tell when ticking it would change nothing. */
public interface BlazeIdleState {
    /** True while the state equals its fresh default, so a tick of it is a no-op. */
    boolean overhaul$idle();
}
