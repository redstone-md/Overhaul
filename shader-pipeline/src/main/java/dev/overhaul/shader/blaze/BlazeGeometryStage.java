package dev.overhaul.shader.blaze;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core programs with a geometry stage, as Lodestone writes them: interface blocks between stages become plain
 * declarations named by block and member, and the geometry stage's own declarations take the schema types the
 * translator assigns locations with.
 */
final class BlazeGeometryStage {
    private static final Pattern BLOCK = Pattern.compile("\\b(in|out)\\s+(\\w+)\\s*\\{([^{}]*)}\\s*(\\w+)?\\s*(\\[\\s*\\d*\\s*])?\\s*;");
    private static final Pattern MEMBER = Pattern.compile("\\s*(?:(flat|smooth|noperspective)\\s+)?(\\w+)\\s+(\\w+)\\s*");
    private static final Pattern INPUT = Pattern.compile("(?m)^[ \\t]*(?:(?:flat|smooth|noperspective)\\s+)?in\\s+(\\w+)\\s+(\\w+)\\s*\\[\\s*\\d*\\s*]\\s*;");
    private static final Pattern OUTPUT = Pattern.compile("(?m)^[ \\t]*(?:(flat|smooth|noperspective)\\s+)?out\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final String PREFIX = "blk_";

    record Stage(String source, Map<String,String> inputs, Map<String,String> outputs, Set<String> flatOutputs) {}

    private BlazeGeometryStage() {}

    /** Every member is addressed through its instance name; a block without one has no stable name to match by. */
    static String flatten(String source) {
        Matcher block = BLOCK.matcher(source);
        StringBuilder result = new StringBuilder();
        Map<String,String> instances = new LinkedHashMap<>();
        Set<String> arrayed = new HashSet<>();
        while (block.find()) {
            String direction = block.group(1), name = block.group(2), instance = block.group(4);
            if (instance == null) throw new UnsupportedShaderException("Interface block without an instance name: " + name);
            boolean array = block.group(5) != null;
            StringBuilder declarations = new StringBuilder("\n");
            for (String member : block.group(3).split(";")) {
                if (member.isBlank()) continue;
                Matcher field = MEMBER.matcher(member);
                if (!field.matches()) throw new UnsupportedShaderException("Unsupported interface block member in " + name + ": " + member.trim());
                if (field.group(1) != null) declarations.append(field.group(1)).append(' ');
                declarations.append(direction).append(' ').append(field.group(2)).append(' ').append(PREFIX).append(name).append('_').append(field.group(3))
                        .append(array ? "[];" : ";").append('\n');
            }
            instances.put(instance, name);
            if (array) arrayed.add(instance);
            block.appendReplacement(result, Matcher.quoteReplacement(declarations.toString()));
        }
        block.appendTail(result);
        String text = result.toString();
        for (var entry : instances.entrySet()) {
            String instance = Pattern.quote(entry.getKey()), member = Matcher.quoteReplacement(PREFIX + entry.getValue() + "_");
            text = arrayed.contains(entry.getKey())
                    ? text.replaceAll("\\b" + instance + "\\s*\\[([^\\[\\]]*)]\\s*\\.\\s*(\\w+)", member + "$2[$1]")
                    : text.replaceAll("\\b" + instance + "\\s*\\.\\s*(\\w+)", member + "$1");
            if (Pattern.compile("\\b" + instance + "\\b").matcher(text).find()) throw new UnsupportedShaderException("Interface block used as a whole: " + entry.getKey());
        }
        return text;
    }

    /** Declarations carry schema types; {@link #retype} restores the written types once locations exist. */
    static Stage lower(String source) {
        String text = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", "");
        Map<String,String> inputs = new LinkedHashMap<>(), outputs = new LinkedHashMap<>();
        Set<String> flat = new HashSet<>();
        Matcher input = INPUT.matcher(text);
        StringBuilder result = new StringBuilder();
        while (input.find()) {
            if (inputs.putIfAbsent(input.group(2), input.group(1)) != null) throw new UnsupportedShaderException("Duplicate geometry input: " + input.group(2));
            input.appendReplacement(result, Matcher.quoteReplacement("\nin " + BlazeSourceLowering.schemaType(input.group(1)) + " " + input.group(2) + "[];\n"));
        }
        input.appendTail(result);
        Matcher output = OUTPUT.matcher(result.toString());
        result = new StringBuilder();
        while (output.find()) {
            String qualifier = output.group(1), type = output.group(2), name = output.group(3);
            if (qualifier != null && !qualifier.equals("flat")) throw new UnsupportedShaderException("Geometry output qualifier requires explicit lowering: " + qualifier);
            if (qualifier != null) flat.add(name);
            else if (type.startsWith("i") || type.startsWith("u")) throw new UnsupportedShaderException("Integer varying must be flat: " + name);
            if (outputs.putIfAbsent(name, type) != null) throw new UnsupportedShaderException("Duplicate geometry output: " + name);
            output.appendReplacement(result, Matcher.quoteReplacement("\nout " + BlazeSourceLowering.schemaType(type) + " " + name + ";\n"));
        }
        output.appendTail(result);
        return new Stage(result.toString(), Map.copyOf(inputs), Map.copyOf(outputs), Set.copyOf(flat));
    }

    /** The translated declaration of {@code name}, at whatever location it was given, with its written type and qualifier. */
    static String retype(String source, String direction, String name, String type, boolean flat, boolean array) {
        String schema = Pattern.quote(BlazeSourceLowering.schemaType(type)), suffix = array ? "\\[\\s*]" : "";
        Matcher declaration = Pattern.compile("layout\\(location=(\\d+)\\) " + direction + " " + schema + " " + Pattern.quote(name) + suffix + ";").matcher(source);
        if (!declaration.find()) throw new UnsupportedShaderException("Unrecognized translated geometry declaration: " + name);
        String replacement = "layout(location=" + declaration.group(1) + ") " + (flat ? "flat " : "") + direction + " " + type + " " + name + (array ? "[]" : "") + ";";
        return source.substring(0, declaration.start()) + replacement + source.substring(declaration.end());
    }
}
