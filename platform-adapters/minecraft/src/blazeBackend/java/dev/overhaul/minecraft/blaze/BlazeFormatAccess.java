package dev.overhaul.minecraft.blaze;

/** Immutable native layout attached to its actual game format, without a global format cache. */
public interface BlazeFormatAccess {
    BlazeMeshState.Layout overhaul$layout();
}
