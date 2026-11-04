package dev.overhaul.shader.pack;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core-profile programs name the inputs Minecraft's own shaders use. Each maps onto the legacy contract, so
 * they share its decoding and matrices. A name is rewritten only where the program declares it on its own
 * line: a local variable of the same name stays, and a multi-declaration falls through to the interface check.
 * Geometry reaches the vertex stage already camera-relative and tinted, so chunkOffset and colorModulator are
 * neutral, and vaUV1 is vanilla's "no overlay" coordinate because scene geometry carries no overlay.
 */
final class PackCoreProfile {
    private static final Map<String,String> INPUTS = Map.of("vaPosition","gl_Vertex.xyz","vaColor","gl_Color","vaNormal","gl_Normal",
            "vaUV0","gl_MultiTexCoord0.xy","vaUV1","ivec2(0,10)","vaUV2","ivec2(gl_MultiTexCoord1.xy)");
    private static final Map<String,String> UNIFORMS = Map.of("modelViewMatrix","gl_ModelViewMatrix","modelViewMatrixInverse","gl_ModelViewMatrixInverse",
            "projectionMatrix","gl_ProjectionMatrix","projectionMatrixInverse","gl_ProjectionMatrixInverse","normalMatrix","gl_NormalMatrix",
            "textureMatrix","gl_TextureMatrix[0]","chunkOffset","vec3(0.0)","colorModulator","vec4(1.0)");

    private PackCoreProfile() {}

    static String lower(String source) {
        String lowered = source;
        for (var input : INPUTS.entrySet()) lowered = rewrite(lowered,"(?:in|attribute)",input.getKey(),input.getValue());
        for (var uniform : UNIFORMS.entrySet()) lowered = rewrite(lowered,"uniform",uniform.getKey(),uniform.getValue());
        return lowered;
    }

    private static String rewrite(String source, String kind, String name, String replacement) {
        Matcher declaration = Pattern.compile("(?m)^[ \\t]*"+kind+"\\s+\\w+\\s+"+name+"\\s*;").matcher(source);
        if (!declaration.find()) return source;
        return declaration.replaceAll("").replaceAll("\\b"+name+"\\b",Matcher.quoteReplacement(replacement));
    }
}
