package dev.overhaul.shader.compat;

/** The adapter must select coordinates intentionally rather than converting twice. */
public enum CoordinateConvention {
    /** Shader and supplied matrices already produce Vulkan clip and framebuffer coordinates. */
    NATIVE_VULKAN,
    /** Supplied matrices retain OpenGL clip Z [-w,w]; the generated entry point flips Y and maps Z. */
    OPENGL_LEGACY
}
