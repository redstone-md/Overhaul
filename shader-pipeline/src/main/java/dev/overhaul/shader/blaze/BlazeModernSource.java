package dev.overhaul.shader.blaze;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites what current OpenGL sources declare into the shape the Blaze lowering reads: uniform blocks become loose
 * uniforms named after their block, or read-only storage buffers when they hold arrays or structs, explicit locations go because every input binds by name, and a vertex shader that
 * only counts vertices reads the count from a position attribute.
 */
public final class BlazeModernSource {
    private static final Pattern BLOCK = Pattern.compile("(?:\\blayout\\s*\\([^)]*\\)\\s*)?\\buniform\\s+(\\w+)\\s*\\{([^}]*)\\}\\s*(\\w+)?\\s*;");
    private static final Pattern FIELD = Pattern.compile("(?:\\blayout\\s*\\([^)]*\\)\\s*)?(?:\\b(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern MANY = Pattern.compile("\\buniform\\s+(\\w+)\\s+(\\w+(?:\\s*,\\s*\\w+)+)\\s*;");
    private static final Pattern LAYOUT = Pattern.compile("\\blayout\\s*\\([^)]*\\)\\s*(?=(?:(?:flat|smooth|centroid|noperspective)\\s+)*(?:in|out|uniform)\\b)");
    private static final Pattern INTERPOLATION = Pattern.compile("\\b(?:smooth|centroid)\\s+(?=(?:flat\\s+)?(?:in|out)\\b)");
    private static final Pattern ATTRIBUTE = Pattern.compile("(?:^|[;}\\s])in\\s+\\w+\\s+\\w+\\s*;");
    private static final Pattern ARRAY = Pattern.compile("\\buniform\\s+(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*\\]\\s*(\\w+)|\\s(\\w+)\\s*\\[\\s*(\\d+)\\s*\\])\\s*;");
    /** Joins an array's name to an element's index in the loose uniform that replaces it. */
    public static final String ELEMENT = "_ovElem";
    private static final Pattern PRIMITIVE = Pattern.compile("\\blayout\\s*\\([^)]*\\)\\s*(?:in|out)\\s*;");
    private static final Pattern VERSION =Pattern.compile("(?m)^\\s*#version[^\\r\\n]*");

    private BlazeModernSource() {}

    static String normalise(String source, boolean vertex) {
        String text = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "")
                .replaceAll("(?m)^\\s*#extension[^\\r\\n]*", "")
                .replaceAll("\\bprecision\\s+\\w+\\s+\\w+\\s*;", "");
        text = blocks(text);
        text = LAYOUT.matcher(text).replaceAll("");
        text = INTERPOLATION.matcher(text).replaceAll("");
        text = separate(text);
        text = arrays(text);
        if (!vertex) return text;
        if (text.matches("(?s).*\\bin\\s+vec2\\s+UV\\s*;.*")) text = text.replaceAll("\\bUV\\b", "UV0");
        if (text.contains("gl_VertexID") && !ATTRIBUTE.matcher(text).find()) text = countedVertices(text);
        return text;
    }

    /** A geometry stage keeps its primitive layouts, which name no variable and so carry no location to drop. */
    static String normaliseGeometry(String source) {
        Matcher primitive = PRIMITIVE.matcher(source);
        StringBuilder layouts = new StringBuilder("\n");
        while (primitive.find()) layouts.append(primitive.group()).append('\n');
        String text = normalise(PRIMITIVE.matcher(source).replaceAll(""), false);
        Matcher version = VERSION.matcher(text);
        return version.find() ? text.substring(0, version.end()) + layouts + text.substring(version.end()) : layouts + text;
    }

    private static String blocks(String text) {
        Matcher block = BLOCK.matcher(text);
        StringBuilder result = new StringBuilder();
        StringBuilder renamed = new StringBuilder();
        java.util.List<String[]> names = new java.util.ArrayList<>();
        while (block.find()) {
            String instance = block.group(3), body = block.group(2);
            renamed.setLength(0);
            if (!flat(body)) {
                // Arrays and structs have no loose-uniform shape; the block keeps its std140 bytes as a read-only storage buffer.
                renamed.append("layout(std140, binding = ").append(BlazeBlockBuffers.index(block.group(1))).append(") readonly buffer ")
                        .append(block.group(1)).append(" {").append(body).append('}').append(instance == null ? "" : " " + instance).append(';');
                block.appendReplacement(result, Matcher.quoteReplacement(renamed.toString()));
                continue;
            }
            Matcher field = FIELD.matcher(body);
            while (field.find()) {
                String name = instance == null ? field.group(2) : instance + "_" + field.group(2);
                renamed.append("uniform ").append(field.group(1)).append(' ').append(name).append(';');
                if (instance != null) names.add(new String[] {instance, field.group(2), name});
            }
            block.appendReplacement(result, Matcher.quoteReplacement(renamed.toString()));
        }
        block.appendTail(result);
        String out = result.toString();
        for (String[] name : names) out = out.replaceAll("\\b" + name[0] + "\\s*\\.\\s*" + name[1] + "\\b", name[2]);
        return out;
    }

    /** True when every member is one plain {@code type name;}, so the block can become loose uniforms. */
    private static boolean flat(String body) {
        Matcher field = FIELD.matcher(body);
        int end = 0;
        while (field.find()) {
            if (!body.substring(end, field.start()).isBlank()) return false;
            end = field.end();
        }
        return body.substring(end).isBlank();
    }

    private static String separate(String text) {
        Matcher many = MANY.matcher(text);
        StringBuilder result = new StringBuilder();
        while (many.find()) {
            StringBuilder single = new StringBuilder();
            for (String name : many.group(2).split("\\s*,\\s*")) single.append("uniform ").append(many.group(1)).append(' ').append(name).append(';');
            many.appendReplacement(result, Matcher.quoteReplacement(single.toString()));
        }
        many.appendTail(result);
        return result.toString();
    }

    private static String arrays(String text) {
        Matcher array = ARRAY.matcher(text);
        StringBuilder result = new StringBuilder();
        java.util.List<String> names = new java.util.ArrayList<>();
        while (array.find()) {
            String name = array.group(3) != null ? array.group(3) : array.group(4);
            int length = Integer.parseInt(array.group(2) != null ? array.group(2) : array.group(5));
            names.add(name);
            StringBuilder single = new StringBuilder();
            for (int index = 0; index < length; index++) single.append("uniform ").append(array.group(1)).append(' ').append(name).append(ELEMENT).append(index).append(';');
            array.appendReplacement(result, Matcher.quoteReplacement(single.toString()));
        }
        array.appendTail(result);
        String out = result.toString();
        for (String name : names) {
            out = out.replaceAll("\\b" + name + "\\s*\\[\\s*(\\d+)\\s*\\]", name + ELEMENT + "$1");
            if (Pattern.compile("\\b" + name + "\\s*\\[").matcher(out).find()) throw new UnsupportedShaderException("Uniform array " + name + " has a computed index");
        }
        return out;
    }

    /** gl_VertexIndex includes the base vertex, and a merged or streamed draw has one, so the index travels in the vertex. */
    private static String countedVertices(String text) {
        String body = text.replaceAll("\\bgl_VertexID\\b", "int(Position.x)");
        Matcher version = VERSION.matcher(body);
        if (!version.find()) return "in vec3 Position;\n" + body;
        return body.substring(0, version.end()) + "\nin vec3 Position;\n" + body.substring(version.end());
    }
}
