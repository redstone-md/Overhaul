package dev.overhaul.shader.compat;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A geometry program's per-vertex inputs, {@code in T name[];}, which the declaration scan has no grammar for, and the
 * primitive its {@code layout(...) in;} reads. Its outputs are plain {@code out} declarations and are scanned as a vertex
 * stage's varyings are.
 */
public final class PackGeometryInterface {
    private static final String QUALIFIER = "(?:flat|smooth|noperspective|centroid)";
    private static final Pattern INPUT = Pattern.compile("(?m)^[ \\t]*(?:"+QUALIFIER+"\\s+)?in\\s+(float|vec[234]|u?int|[iu]vec[234]|mat[234])\\s+(\\w+)\\s*\\[\\s*\\d*\\s*]\\s*;");
    private static final Pattern PRIMITIVE = Pattern.compile("\\blayout\\s*\\(\\s*(points|lines_adjacency|lines|triangles_adjacency|triangles)\\s*(?:,[^)]*)?\\)\\s*in\\s*;");

    /** What the geometry stage reads per primitive, which the draw's topology has to match. */
    public enum Primitive { POINTS, LINES, LINES_ADJACENCY, TRIANGLES, TRIANGLES_ADJACENCY }

    record Parsed(String source, Map<String,String> inputs, Primitive primitive) {}

    private PackGeometryInterface() {}

    static Parsed parse(String source) {
        Matcher primitive = PRIMITIVE.matcher(source);
        if (!primitive.find()) throw new UnsupportedShaderException("Geometry program declares no input primitive");
        Primitive input = Primitive.valueOf(primitive.group(1).toUpperCase(java.util.Locale.ROOT));
        if (primitive.find()) throw new UnsupportedShaderException("Geometry program declares its input primitive twice");
        Map<String,String> inputs = new TreeMap<>();
        Matcher matcher = INPUT.matcher(source);
        while (matcher.find()) {
            String prior = inputs.putIfAbsent(matcher.group(2),matcher.group(1));
            if (prior != null && !prior.equals(matcher.group(1))) throw new UnsupportedShaderException("Conflicting type for geometry input "+matcher.group(2));
        }
        return new Parsed(INPUT.matcher(source).replaceAll(""),inputs,input);
    }

    /** Each input sits at the location the vertex stage writes it to. */
    static String declarations(Map<String,String> inputs, Map<String,Integer> locations) {
        StringBuilder result = new StringBuilder();
        for (var input : inputs.entrySet()) {
            Integer location = locations.get(input.getKey());
            if (location == null) throw new UnsupportedShaderException("Geometry input has no matching vertex varying: "+input.getKey());
            result.append("layout(location=").append(location).append(") in ").append(input.getValue()).append(' ').append(input.getKey()).append("[];\n");
        }
        return result.toString();
    }
}
