package dev.overhaul.minecraft.shader;

/** Captured call origin, independent of render-type names and shared vertex programs. */
public enum SceneDrawDomain {
    UNKNOWN, ENTITY, BLOCK_ENTITY;

    public static final String DEFINE = "OVERHAUL_SCENE_DOMAIN";
}
