package dev.overhaul.minecraft.blaze;

import dev.overhaul.shader.blaze.BlazeProgramCompiler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the game's own solid and cutout terrain shaders into ones that leave the lighting to {@link BlazeShading}. The
 * vertex stage keeps the light the game's lightmap gives each vertex and passes on the normal and the light levels;
 * the fragment stage drops the fog, divides that light and the game's face shading back out of its colour, so the
 * colour target holds the surface's own colour, and writes the surface to a second output: the folded normal, the
 * block light level, then the sky light level, kept above zero so a written pixel always tells itself apart. Both
 * stages keep the shader's own body under another name, and every name added avoids the {@code overhaul} prefix the
 * translator keeps for itself, so resource packs that replace terrain shaders keep working.
 */
final class BlazeShadingSource {
    private static final Pattern TERRAIN = Pattern.compile("\\brendertype_(?:solid|cutout|cutout_mipped)\\.vsh\\b"),
            MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)"),
            NORMAL = Pattern.compile("\\bin\\s+vec3\\s+Normal\\s*;"),
            LIGHTMAP = Pattern.compile("\\bminecraft_sample_lightmap\\s*\\("),
            FOG = Pattern.compile("\\blinear_fog\\s*\\("),
            OUTPUT = Pattern.compile("\\bout\\s+vec4\\s+fragColor\\s*;");

    private static final String VERTEX_HEAD = """
            out vec4 shadingLight;
            out vec4 shadingSurface;
            out vec2 shadingLevels;
            vec4 shading_lightmap(sampler2D lightMap, ivec2 uv) {
                shadingLight = minecraft_sample_lightmap(lightMap, uv);
                return shadingLight;
            }
            #define minecraft_sample_lightmap shading_lightmap
            """;
    private static final String VERTEX_MAIN = """

            void main() {
                shadingLight = vec4(1.0);
                shading_main();
                shadingSurface = vec4(Normal, 0.0);
                shadingLevels = vec2(UV2) / 240.0;
            }
            """;
    // The fog's values stay read where nothing can tell they are never used, so the program takes the same uniforms.
    private static final String FRAGMENT_HEAD = """
            in vec4 shadingLight;
            in vec4 shadingSurface;
            in vec2 shadingLevels;
            vec4 shading_unfogged(vec4 color, float distance, float start, float end, vec4 fogColor) {
                return end < start - 1e30 ? fogColor * distance : color;
            }
            #define linear_fog shading_unfogged
            """;
    private static final String FRAGMENT_MAIN = """

            void main() {
                shading_main();
                vec3 normal = normalize(shadingSurface.xyz);
                vec3 axes = abs(normal);
                float shade = axes.y > 0.9 ? (normal.y > 0.0 ? 1.0 : 0.5) : axes.z > 0.9 ? 0.8 : axes.x > 0.9 ? 0.6 : 1.0;
                fragColor.rgb = min(fragColor.rgb / max(shadingLight.rgb * shade, vec3(0.004)), vec3(1.0));
                vec2 folded = normal.xy / (axes.x + axes.y + axes.z);
                if (normal.z < 0.0) folded = (1.0 - abs(folded.yx)) * vec2(folded.x >= 0.0 ? 1.0 : -1.0, folded.y >= 0.0 ? 1.0 : -1.0);
                vec2 levels = clamp(shadingLevels, 0.0, 1.0);
                gl_FragData[1] = vec4(folded * 0.5 + 0.5, levels.x, (1.0 + levels.y * 254.0) / 255.0);
            }
            """;

    private BlazeShadingSource() {}

    /** Whether the program is one of the game's solid or cutout terrain shaders, the ones shaded this way. */
    static boolean terrain(String programName) {
        return TERRAIN.matcher(programName).find();
    }

    /** The vertex stage passing on the surface, or null when it does not light vertices through the game's lightmap. */
    static String vertex(String source) {
        if (!NORMAL.matcher(source).find()) return null;
        return wrap(source, LIGHTMAP, VERTEX_HEAD, VERTEX_MAIN);
    }

    /** The fragment stage writing the surface, or null when it does not fog and write its colour the usual way. */
    static String fragment(String source) {
        if (!OUTPUT.matcher(source).find()) return null;
        return wrap(source, FOG, FRAGMENT_HEAD, FRAGMENT_MAIN);
    }

    /** Whether the shaded program takes exactly the uniforms and samplers of the one it stands in for. */
    static boolean replaces(BlazeProgramCompiler.Program shaded, BlazeProgramCompiler.Program original) {
        var bindings = shaded.bindings();
        return bindings.uniformBytes() == original.bindings().uniformBytes() && bindings.uniforms().equals(original.bindings().uniforms())
                && bindings.samplers().equals(original.bindings().samplers());
    }

    /**
     * Renames the shader's main, puts {@code head} before it and the new main after everything. The function
     * {@code used} names has to be defined above main, where the head's macro no longer reaches it, and called in it.
     */
    private static String wrap(String source, Pattern used, String head, String main) {
        Matcher entry = MAIN.matcher(source);
        if (!entry.find()) return null;
        Matcher call = used.matcher(source);
        if (!call.find() || call.start() > entry.start()) return null;
        if (!call.find(entry.end())) return null;
        return source.substring(0, entry.start()) + head + "void shading_main()" + source.substring(entry.end()) + main;
    }
}
