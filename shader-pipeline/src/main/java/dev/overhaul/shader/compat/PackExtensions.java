package dev.overhaul.shader.compat;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translated programs are GLSL 450, so an extension that became core needs no directive. Anything else
 * only fails if required: a pack that merely enables one still compiles unless it uses what is missing.
 */
final class PackExtensions {
    private static final Pattern DIRECTIVE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+(\\w+)[ \\t]*:[ \\t]*(\\w+)[ \\t]*$");
    private static final Set<String> CORE = Set.of("GL_ARB_shader_texture_lod","GL_EXT_gpu_shader4","GL_EXT_gpu_shader4_1","GL_ARB_gpu_shader5","GL_ARB_explicit_attrib_location",
            "GL_ARB_explicit_uniform_location","GL_ARB_shading_language_packing","GL_ARB_shading_language_420pack","GL_ARB_texture_gather",
            "GL_ARB_texture_query_lod","GL_ARB_texture_query_levels","GL_ARB_shader_bit_encoding","GL_ARB_derivative_control","GL_ARB_conservative_depth",
            "GL_ARB_shader_image_load_store","GL_ARB_shader_image_size","GL_ARB_texture_rectangle","GL_ARB_uniform_buffer_object","GL_ARB_enhanced_layouts",
            "GL_ARB_arrays_of_arrays","GL_ARB_fragment_coord_conventions","GL_ARB_shader_storage_buffer_object","GL_ARB_compute_shader");
    /** Spellings GL_EXT_gpu_shader4 added for what core GLSL names differently. */
    private static final Map<String,String> FUNCTIONS = Map.of("texelFetch2D","texelFetch","textureSize2D","textureSize","texture2DGrad","textureGrad","texture2DProjGrad","textureProjGrad","texelFetch2DOffset","texelFetchOffset");

    private PackExtensions() {}

    static String lower(String source) {
        Matcher matcher = DIRECTIVE.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (!CORE.contains(matcher.group(1)) && matcher.group(2).equals("require")) {
                throw new UnsupportedShaderException("Pack requires an unavailable extension: "+matcher.group(1));
            }
            matcher.appendReplacement(result,"");
        }
        matcher.appendTail(result);
        String lowered = result.toString();
        for (var function : FUNCTIONS.entrySet()) lowered = lowered.replaceAll("\\b"+function.getKey()+"\\b",function.getValue());
        return lowered;
    }
}
