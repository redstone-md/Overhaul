package dev.overhaul.shader.blaze;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quad-patch tessellation as a geometry stage. A control stage that only copies its four vertices and sets every level
 * to one integer leaves the evaluation stage to run on a fixed grid, so the geometry stage walks that grid, runs the
 * evaluation code once per grid point and emits each row as a triangle strip. The patch reaches it as one
 * lines-with-adjacency primitive, whose four vertices are the patch's four in order.
 */
final class BlazeTessellationStage {
    private static final int MAX_LEVEL = 8, MAX_COMPONENTS = 1024;
    private static final String PATCH = "ovPatch_";
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\r\\n]*");
    private static final Pattern VERTICES = Pattern.compile("\\blayout\\s*\\(\\s*vertices\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s*;");
    private static final Pattern ARRAY = Pattern.compile("\\b(in|out)\\s+(\\w+)\\s+(\\w+)\\s*\\[\\s*\\d*\\s*]\\s*;");
    private static final Pattern LEVEL = Pattern.compile("\\bgl_TessLevel(Inner|Outer)\\s*\\[\\s*(\\d)\\s*]\\s*=\\s*(\\d+)(?:\\.0*)?\\s*;");
    private static final Pattern POSITION = Pattern.compile("\\bgl_out\\s*\\[\\s*gl_InvocationID\\s*]\\s*\\.\\s*gl_Position\\s*=\\s*gl_in\\s*\\[\\s*gl_InvocationID\\s*]\\s*\\.\\s*gl_Position\\s*;");
    private static final Pattern COPY = Pattern.compile("\\b(\\w+)\\s*\\[\\s*gl_InvocationID\\s*]\\s*=\\s*(\\w+)\\s*\\[\\s*gl_InvocationID\\s*]\\s*;");
    private static final Pattern FIRST = Pattern.compile("\\bif\\s*\\(\\s*gl_InvocationID\\s*==\\s*0\\s*\\)");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Pattern DOMAIN = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)\\s*in\\s*;");
    private static final Pattern OUTPUT = Pattern.compile("(?m)^[ \\t]*(?:flat\\s+)?out\\s+(\\w+)\\s+\\w+\\s*;");

    record Lowered(String vertex, String geometry) {}

    private BlazeTessellationStage() {}

    /** Every source is preprocessed. The vertex stage's outputs take the names the geometry stage reads them by. */
    static Lowered lower(String vertex, String control, String evaluation) {
        Map<String, String> copies = new LinkedHashMap<>();
        int level = control(strip(control), copies);
        String vs = vertex;
        String tes = strip(evaluation);
        for (var copy : copies.entrySet()) {
            vs = vs.replaceAll("\\b" + copy.getValue() + "\\b", PATCH + copy.getValue());
            tes = tes.replaceAll("\\b" + copy.getKey() + "\\b", PATCH + copy.getValue());
        }
        return new Lowered(vs, geometry(tes, level));
    }

    private static String strip(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
    }

    /** The one level the control stage sets everywhere; each output copied from the input of the same type. */
    private static int control(String source, Map<String, String> copies) {
        Matcher vertices = VERTICES.matcher(source);
        if (!vertices.find() || !vertices.group(1).equals("4")) throw new UnsupportedShaderException("Tessellation control stage has no four-vertex patch");
        String rest = VERTICES.matcher(source).replaceAll("");
        Map<String, String> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
        Matcher array = ARRAY.matcher(rest);
        while (array.find()) (array.group(1).equals("in") ? inputs : outputs).put(array.group(3), array.group(2));
        rest = ARRAY.matcher(rest).replaceAll("");
        int level = -1, levels = 0;
        Matcher set = LEVEL.matcher(rest);
        while (set.find()) {
            int value = Integer.parseInt(set.group(3));
            if (level >= 0 && value != level) throw new UnsupportedShaderException("Tessellation levels differ");
            level = value;
            levels++;
        }
        if (levels != 6 || level < 1 || level > MAX_LEVEL) throw new UnsupportedShaderException("Tessellation control stage sets no single level from 1 to " + MAX_LEVEL);
        rest = LEVEL.matcher(rest).replaceAll("");
        if (!POSITION.matcher(rest).find()) throw new UnsupportedShaderException("Tessellation control stage does not pass the position through");
        rest = POSITION.matcher(rest).replaceAll("");
        Matcher copy = COPY.matcher(rest);
        while (copy.find()) {
            String out = copy.group(1), in = copy.group(2);
            if (!outputs.containsKey(out) || !outputs.get(out).equals(inputs.get(in)) || copies.putIfAbsent(out, in) != null)
                throw new UnsupportedShaderException("Tessellation control stage copy is not a plain pass-through: " + out);
        }
        if (!copies.keySet().equals(outputs.keySet())) throw new UnsupportedShaderException("Tessellation control stage computes an output");
        rest = COPY.matcher(rest).replaceAll("");
        rest = FIRST.matcher(MAIN.matcher(rest.replaceAll("(?m)^\s*#[^\r\n]*", "")).replaceAll("")).replaceAll("");
        rest = rest.replaceAll("\\buniform\\s+\\w+\\s+\\w+\\s*;", "").replaceAll("[{}\\s]", "");
        if (!rest.isEmpty()) throw new UnsupportedShaderException("Tessellation control stage does more than pass a patch through");
        return level;
    }

    private static String geometry(String evaluation, int level) {
        Matcher domain = DOMAIN.matcher(evaluation);
        if (!domain.find()) throw new UnsupportedShaderException("Tessellation evaluation stage declares no domain");
        for (String part : domain.group(1).split(",")) {
            String word = part.trim();
            if (!word.equals("quads") && !word.equals("equal_spacing") && !word.equals("ccw"))
                throw new UnsupportedShaderException("Tessellation evaluation layout has no geometry form: " + word);
        }
        if (!domain.group(1).contains("quads")) throw new UnsupportedShaderException("Tessellation evaluation stage is not a quad domain");
        String body = DOMAIN.matcher(evaluation).replaceAll("");
        if (Pattern.compile("\\bgl_(?:TessLevel\\w*|PatchVerticesIn|PrimitiveID)\\b|\\bpatch\\s+in\\b").matcher(body).find())
            throw new UnsupportedShaderException("Tessellation evaluation stage reads patch state");
        if (!MAIN.matcher(body).find()) throw new UnsupportedShaderException("Tessellation evaluation stage has no main");
        body = MAIN.matcher(body.replaceAll("\\bgl_TessCoord\\b", "ovTessCoord")).replaceFirst("void ovEvaluate()");
        int vertices = 2 * level * (level + 1);
        if (vertices * components(body) > MAX_COMPONENTS) throw new UnsupportedShaderException("Tessellated patch exceeds the geometry output budget");
        String header = "\nlayout(lines_adjacency) in;\nlayout(triangle_strip, max_vertices = " + vertices + ") out;\nvec3 ovTessCoord;\n";
        Matcher version = VERSION.matcher(body);
        String text = version.find() ? body.substring(0, version.end()) + header + body.substring(version.end()) : "#version 150" + header + body;
        String size = "float(" + level + ")";
        return text + "\nvoid main() {\n    for (int i = 0; i < " + level + "; i++) {\n        for (int j = 0; j <= " + level + "; j++) {\n"
                + "            ovTessCoord = vec3(float(i) / " + size + ", float(j) / " + size + ", 0.0);\n            ovEvaluate();\n            EmitVertex();\n"
                + "            ovTessCoord = vec3(float(i + 1) / " + size + ", float(j) / " + size + ", 0.0);\n            ovEvaluate();\n            EmitVertex();\n"
                + "        }\n        EndPrimitive();\n    }\n}\n";
    }

    private static int components(String source) {
        int total = 4;
        Matcher output = OUTPUT.matcher(source);
        while (output.find()) {
            String type = output.group(1);
            char last = type.charAt(type.length() - 1);
            total += type.startsWith("mat") ? 16 : Character.isDigit(last) ? last - '0' : 1;
        }
        return total;
    }
}
