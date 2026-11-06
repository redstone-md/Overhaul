package dev.overhaul.shader.pack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Macros a shader loader defines for every pack. MC_NORMAL_MAP and MC_SPECULAR_MAP stay undefined because no
 * PBR maps are stitched. IS_IRIS is defined like Iris does: packs gate custom images and Iris-only uniforms on it.
 */
public final class PackBuiltinMacros {
    private static final String GLSL_VERSION = "450";
    /** How far a loader squeezes the hand's depth around the middle of the range, which packs read to find the hand. */
    public static final String HAND_DEPTH = "0.125";

    private PackBuiltinMacros() {}

    /** Explicit definitions win, so a user can still override what a pack sees. */
    public static Map<String,String> with(Map<String,String> explicit, String minecraftVersion, String operatingSystem) {
        Map<String,String> macros = new LinkedHashMap<>();
        macros.put("MC_VERSION",Integer.toString(version(minecraftVersion)));
        macros.put("MC_GL_VERSION",GLSL_VERSION);
        macros.put("MC_GLSL_VERSION",GLSL_VERSION);
        macros.put(system(operatingSystem),"1");
        macros.put("MC_RENDER_QUALITY","1.0");
        macros.put("MC_SHADOW_QUALITY","1.0");
        macros.put("MC_HAND_DEPTH",HAND_DEPTH);
        // Iris identity and feature flags for what this loader runs; packs gate image.* and .csh paths on them.
        macros.put("IS_IRIS","1");
        macros.put("IRIS_FEATURE_CUSTOM_IMAGES","1");
        macros.put("IRIS_FEATURE_COMPUTE_SHADERS","1");
        PackRenderStages.define(macros);
        macros.putAll(explicit);
        return macros;
    }

    /** 1.21.1 reads 12101 and 26.3 reads 260300; a snapshot name has no number and reads as the newest. */
    static int version(String name) {
        String[] parts = name.split("[.]");
        try {
            int major = Integer.parseInt(parts[0]), minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0, patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major*10000+minor*100+patch;
        } catch (NumberFormatException snapshot) {
            return Integer.MAX_VALUE;
        }
    }

    private static String system(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("win")&&!lower.contains("darwin")) return "MC_OS_WINDOWS";
        if (lower.contains("mac")||lower.contains("darwin")) return "MC_OS_MAC";
        if (lower.contains("bsd")) return "MC_OS_BSD";
        return "MC_OS_LINUX";
    }
}
