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

    private static final Pattern TRANSLUCENT = Pattern.compile("\\brendertype_translucent\\.vsh\\b");
    /** The uniforms a mirrored program takes beyond the game's: the light's direction and strength, the sky, the eye and the time. */
    static final String SUN = "ShadingSun", SKY = "ShadingSky", EYE = "ShadingEye";

    private static final String MIRROR_VERTEX_HEAD = """
            uniform vec4 ShadingEye;
            out vec3 shadingNormal;
            out vec3 shadingToward;
            out vec3 shadingPlace;
            """;
    private static final String MIRROR_VERTEX_MAIN = """

            void main() {
                shading_main();
                vec4 seen = inverse(ProjMat) * gl_Position;
                shadingToward = transpose(mat3(ModelViewMat)) * (seen.xyz / seen.w);
                shadingPlace = shadingToward + ShadingEye.xyz;
                shadingNormal = Normal;
            }
            """;
    private static final String MIRROR_FRAGMENT_HEAD = """
            uniform vec4 ShadingSun;
            uniform vec4 ShadingSky;
            uniform vec4 ShadingEye;
            in vec3 shadingNormal;
            in vec3 shadingToward;
            in vec3 shadingPlace;
            """;
    // A surface seen at a shallow angle shows the sky above it and the sun's glint; one facing up also ripples.
    private static final String MIRROR_FRAGMENT_MAIN = """

            void main() {
                shading_main();
                vec3 normal = normalize(shadingNormal);
                float up = step(0.9, normal.y);
                vec2 at = shadingPlace.xz;
                float time = ShadingEye.w;
                vec2 ripple = vec2(sin(at.x * 1.7 + at.y * 0.6 + time * 1.3) + sin(at.x * 3.9 - at.y * 2.3 - time * 1.9) * 0.5,
                        sin(at.y * 1.9 - at.x * 0.7 + time * 1.1) + sin(at.y * 4.3 + at.x * 2.9 + time * 2.3) * 0.5);
                ripple += vec2(sin(at.x * 9.1 + at.y * 6.7 + time * 2.9), sin(at.y * 8.3 - at.x * 7.1 - time * 3.1)) * 0.35;
                normal = normalize(normal + vec3(ripple.x, 0.0, ripple.y) * 0.03 * up);
                float far = length(shadingToward);
                vec3 toward = shadingToward / max(far, 1e-4);
                float facing = clamp(dot(-toward, normal), 0.0, 1.0);
                float mirror = (0.03 + 0.97 * pow(1.0 - facing, 5.0)) * (0.35 + 0.65 * up);
                vec3 bounced = reflect(toward, normal);
                vec3 above = mix(FogColor.rgb, ShadingSky.rgb, clamp(bounced.y * 2.5, 0.0, 1.0));
                float day = step(0.5, ShadingSky.w);
                vec3 sun = mix(vec3(0.3, 0.36, 0.55), mix(vec3(1.0, 0.6, 0.28), vec3(1.0, 0.96, 0.88), smoothstep(0.0, 0.45, ShadingSun.y)), day);
                float glint = pow(max(dot(bounced, ShadingSun.xyz), 0.0), 220.0) * ShadingSun.w * up;
                float clear = 1.0 - smoothstep(FogStart, FogEnd, far);
                fragColor.rgb = mix(mix(fragColor.rgb, above, mirror * clear), sun, min(glint * 3.0, 1.0) * clear);
                fragColor.a = max(fragColor.a, mirror * clear);
            }
            """;

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
                gl_FragData[1] = vec4(folded * 0.5 + 0.5, (floor(levels.x * 127.0 + 0.5) * 2.0 + SHADING_SOFT) / 255.0, (1.0 + levels.y * 254.0) / 255.0);
            }
            """;

    private BlazeShadingSource() {}

    /** Whether the program is one of the game's solid or cutout terrain shaders, the ones shaded this way. */
    /** Whether the terrain program draws cut-out surfaces: leaves, plants and the like, thin enough for the sun to pass through. */
    static boolean soft(String programName) {
        return programName.contains("rendertype_cutout");
    }

    static boolean terrain(String programName) {
        return TERRAIN.matcher(programName).find();
    }

    /** Whether the program is the game's translucent terrain shader, the one whose surfaces mirror the sky. */
    static boolean translucent(String programName) {
        return TRANSLUCENT.matcher(programName).find();
    }

    /** The translucent vertex stage passing on where each surface is and faces, or null when it has no normal. */
    static String mirrorVertex(String source) {
        if (!NORMAL.matcher(source).find()) return null;
        return rename(source, MIRROR_VERTEX_HEAD, MIRROR_VERTEX_MAIN);
    }

    /** The translucent fragment stage mirroring the sky, or null when it does not fog and write its colour the usual way. */
    static String mirrorFragment(String source) {
        if (!OUTPUT.matcher(source).find() || !FOG.matcher(source).find()) return null;
        return rename(source, MIRROR_FRAGMENT_HEAD, MIRROR_FRAGMENT_MAIN);
    }

    /** Whether the mirrored program takes every uniform of the one it stands in for, the same samplers, and the light. */
    static boolean widens(BlazeProgramCompiler.Program mirrored, BlazeProgramCompiler.Program original) {
        var uniforms = mirrored.bindings().uniforms();
        for (var entry : original.bindings().uniforms().entrySet()) {
            var same = uniforms.get(entry.getKey());
            if (same == null || !same.type().equals(entry.getValue().type()) || same.byteSize() != entry.getValue().byteSize()) return false;
        }
        return mirrored(mirrored) && uniforms.containsKey(SKY) && uniforms.containsKey(EYE)
                && mirrored.bindings().samplers().equals(original.bindings().samplers());
    }

    /** Whether the program is one {@link #widens} passed, so its snapshot needs the light. */
    static boolean mirrored(BlazeProgramCompiler.Program program) {
        return program.bindings().uniforms().containsKey(SUN);
    }

    /** The vertex stage passing on the surface, or null when it does not light vertices through the game's lightmap. */
    static String vertex(String source) {
        if (!NORMAL.matcher(source).find()) return null;
        return wrap(source, LIGHTMAP, VERTEX_HEAD, VERTEX_MAIN);
    }

    /** The fragment stage writing the surface, or null when it does not fog and write its colour the usual way. */
    static String fragment(String source, boolean soft) {
        if (!OUTPUT.matcher(source).find()) return null;
        return wrap(source, FOG, "#define SHADING_SOFT " + (soft ? "1.0" : "0.0") + "\n" + FRAGMENT_HEAD, FRAGMENT_MAIN);
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
    private static String rename(String source, String head, String main) {
        Matcher entry = MAIN.matcher(source);
        if (!entry.find()) return null;
        return source.substring(0, entry.start()) + head + "void shading_main()" + source.substring(entry.end()) + main;
    }

    private static String wrap(String source, Pattern used, String head, String main) {
        Matcher entry = MAIN.matcher(source);
        if (!entry.find()) return null;
        Matcher call = used.matcher(source);
        if (!call.find() || call.start() > entry.start()) return null;
        if (!call.find(entry.end())) return null;
        return source.substring(0, entry.start()) + head + "void shading_main()" + source.substring(entry.end()) + main;
    }
}
