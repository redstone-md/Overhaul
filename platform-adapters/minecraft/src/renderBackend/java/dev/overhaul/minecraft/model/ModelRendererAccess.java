package dev.overhaul.minecraft.model;

/** Instance-owned model feature participant, nullable before registration or after another replacement. */
public interface ModelRendererAccess {
    OverhaulModelRenderer overhaul$models();
}
