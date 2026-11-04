package dev.overhaul.shader.pack;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vertex attributes packs read that captured geometry does not carry yet. Each gets a value that keeps
 * the program running without a block identity, so the materials and motion depending on them degrade:
 * mc_Entity reads -1 (no block.properties mapping), at_velocity and at_midBlock read zero, mc_midTexCoord
 * is the vertex's own texture coordinate, and at_tangent is an arbitrary tangent of the normal.
 */
public final class PackVertexDefaults {
    private static final String NORMAL = "overhaulLegacyNormal", TEXCOORD = "overhaulLegacyTexCoord0";
    private static final Set<String> NAMES = Set.of("mc_Entity","mc_midTexCoord","at_tangent","at_velocity","at_midBlock");

    private PackVertexDefaults() {}

    public static boolean known(String attribute) { return NAMES.contains(attribute); }
    public static List<String> names() { return NAMES.stream().sorted().toList(); }

    /** Statements for the wrapped vertex entry; they run after the decoder has assigned the legacy attributes. */
    public static String assign(Map<String,Integer> attributes, Map<String,String> types) {
        StringBuilder result = new StringBuilder();
        for (String attribute : attributes.keySet()) if (known(attribute)) {
            String type = types.get(attribute);
            result.append(attribute).append('=').append(type).append('(').append(value(attribute,attributes)).append(");\n");
        }
        return result.toString();
    }
    private static String value(String attribute, Map<String,Integer> attributes) {
        return switch (attribute) {
            case "mc_Entity" -> "vec4(-1.0)";
            case "mc_midTexCoord" -> attributes.containsKey(TEXCOORD) ? TEXCOORD : "vec4(0.0,0.0,0.0,1.0)";
            case "at_tangent" -> attributes.containsKey(NORMAL) ? tangent() : "vec4(1.0,0.0,0.0,1.0)";
            default -> "vec4(0.0)";
        };
    }
    /** The normal can be zero when the program never needed it, and normalize would turn that into NaN. */
    private static String tangent() {
        String axis = "abs("+NORMAL+".y)<0.999?vec3(0.0,1.0,0.0):vec3(1.0,0.0,0.0)";
        String cross = "cross("+NORMAL+","+axis+")";
        return "vec4(dot("+cross+","+cross+")>0.0?normalize("+cross+"):vec3(1.0,0.0,0.0),1.0)";
    }
}
