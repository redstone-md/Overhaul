package dev.overhaul.shader.pack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Distant Horizons programs a pack writes for its LOD terrain. They are ordinary gbuffer programs fed by DH's own
 * 16-byte vertex: four unsigned shorts of position and packed light, an RGBA8 colour, then material, normal index and
 * texture tile bytes. The vertex source is rebuilt to read those inputs as floats, so a {@code #version 120} pack keeps
 * compiling without integer arithmetic, and every LOD of a layer goes out in one batched draw with its offset per instance.
 */
public final class PackDhPrograms {
    /** Defined while DH is loaded; a pack only writes its DH paths behind it. */
    public static final String MACRO = "DISTANT_HORIZONS";
    public static final String POSITION = "overhaulDhPosition", DATA = "overhaulDhData";
    /** How far DH nudges a vertex sideways to hide seams between neighbouring LODs. */
    private static final String MICRO_OFFSET = "0.01";
    private static final String[] MATERIALS = {"UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE", "SNOW", "SAND",
        "TERRACOTTA", "NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED"};
    private static final Pattern HEADER = Pattern.compile("\\A(?:[ \\t]*(?:#[ \\t]*(?:version|extension)[^\\n]*)?\\r?\\n)*");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Pattern MATERIAL_DECLARATION = Pattern.compile("(?m)^[ \\t]*(?:uniform|in|attribute|flat\\s+in)\\s+int\\s+dhMaterialId\\s*;");

    private PackDhPrograms() {}

    /** The programs DH draws through: its opaque and its translucent terrain. */
    public static boolean drawn(String name) {
        return name.equals("dh_terrain") || name.equals("dh_water");
    }

    /** What a pack can test its DH paths against, in the order DH numbers its materials. */
    public static Map<String, String> macros() {
        Map<String, String> macros = new LinkedHashMap<>();
        macros.put(MACRO, "1");
        for (int index = 0; index < MATERIALS.length; index++) macros.put("DH_BLOCK_" + MATERIALS[index], Integer.toString(index));
        return macros;
    }

    /** Null when the source has no main to wrap. */
    public static String lower(String vertex) {
        if (!MAIN.matcher(vertex).find()) return null;
        String body = MATERIAL_DECLARATION.matcher(vertex).replaceAll("");
        body = body.replaceAll("\\bftransform\\s*\\(\\s*\\)", "(gl_ModelViewProjectionMatrix * gl_Vertex)");
        body = replace(body, "gl_Vertex", "overhaulDhVertex");
        body = replace(body, "gl_Normal", "overhaulDhNormal");
        body = replace(body, "gl_MultiTexCoord1", "overhaulDhLight");
        body = replace(body, "gl_MultiTexCoord0", "vec4(0.0, 0.0, 0.0, 1.0)");
        body = MAIN.matcher(body).replaceFirst("void overhaulDhMain()");
        Matcher header = HEADER.matcher(body);
        int at = header.find() ? header.end() : 0;
        return body.substring(0, at) + declarations() + body.substring(at) + wrapper();
    }

    private static String declarations() {
        return "attribute vec4 " + POSITION + ";\nattribute vec4 " + DATA + ";\nattribute vec3 " + PackSectionOffsets.ATTRIBUTE + ";\n"
                + "vec4 overhaulDhVertex;\nvec3 overhaulDhNormal;\nvec4 overhaulDhLight;\nint dhMaterialId;\n"
                + "vec3 overhaulDhNormalOf(float index) {\n"
                + "    if (index < 0.5) return vec3(0.0, -1.0, 0.0);\n"
                + "    if (index < 1.5) return vec3(0.0, 1.0, 0.0);\n"
                + "    if (index < 2.5) return vec3(0.0, 0.0, -1.0);\n"
                + "    if (index < 3.5) return vec3(0.0, 0.0, 1.0);\n"
                + "    if (index < 4.5) return vec3(-1.0, 0.0, 0.0);\n"
                + "    return vec3(1.0, 0.0, 0.0);\n}\n"
                + "float overhaulDhBit(float bits, float bit) { return mod(floor(bits / bit), 2.0); }\n";
    }

    /** Decodes the packed meta short: light in the low byte (block high nibble, sky low), seam offsets in the high byte. */
    private static String wrapper() {
        return "\nvoid main() {\n"
                + "    float meta = " + POSITION + ".w;\n"
                + "    float micro = floor(meta / 256.0);\n"
                + "    float lights = meta - micro * 256.0;\n"
                + "    float block = floor(lights / 16.0);\n"
                + "    float sky = lights - block * 16.0;\n"
                + "    float mx = overhaulDhBit(micro, 1.0) * (1.0 - 2.0 * overhaulDhBit(micro, 2.0)) * " + MICRO_OFFSET + ";\n"
                + "    float mz = overhaulDhBit(micro, 16.0) * (1.0 - 2.0 * overhaulDhBit(micro, 32.0)) * " + MICRO_OFFSET + ";\n"
                + "    overhaulDhVertex = vec4(" + POSITION + ".xyz + " + PackSectionOffsets.ATTRIBUTE + " + vec3(mx, 0.0, mz), 1.0);\n"
                + "    overhaulDhNormal = overhaulDhNormalOf(" + DATA + ".y);\n"
                + "    overhaulDhLight = vec4(block * 16.0, sky * 16.0, 0.0, 1.0);\n"
                + "    dhMaterialId = int(" + DATA + ".x + 0.5);\n"
                + "    overhaulDhMain();\n}\n";
    }

    private static String replace(String source, String token, String replacement) {
        return source.replaceAll("\\b" + token + "\\b", Matcher.quoteReplacement(replacement));
    }
}
