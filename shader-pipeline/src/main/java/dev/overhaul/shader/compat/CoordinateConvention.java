package dev.overhaul.shader.compat;

/** The adapter must select coordinates intentionally rather than converting twice. */
public enum CoordinateConvention {
    /** Shader and supplied matrices already produce Vulkan clip and framebuffer coordinates. */
    NATIVE_VULKAN,
    /** Supplied matrices retain OpenGL clip Z [-w,w]; the generated entry point flips Y and maps Z. */
    OPENGL_LEGACY,
    /** Offscreen pack textures and the owned game framebuffer store GL bottom at texel row zero. */
    OPENGL_RENDER_TARGET,
    /** Shared game depth is reversed: supplied GL clip Z is mapped to (w-z)/2, with framebuffer Y unchanged. */
    OPENGL_REVERSED_DEPTH_TARGET
}
