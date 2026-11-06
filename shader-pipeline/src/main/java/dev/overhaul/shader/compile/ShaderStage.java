package dev.overhaul.shader.compile;

import static org.lwjgl.util.shaderc.Shaderc.*;

public enum ShaderStage {
    VERTEX(shaderc_glsl_vertex_shader), GEOMETRY(shaderc_glsl_geometry_shader), FRAGMENT(shaderc_glsl_fragment_shader),
    COMPUTE(shaderc_glsl_compute_shader);

    private final int shadercKind;
    ShaderStage(int shadercKind) { this.shadercKind = shadercKind; }
    int shadercKind() { return shadercKind; }
}
