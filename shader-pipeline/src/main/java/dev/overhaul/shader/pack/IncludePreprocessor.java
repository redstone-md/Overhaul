package dev.overhaul.shader.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands pack-root and relative includes with bounded work and source-number mapping. */
public final class IncludePreprocessor {
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*(?://.*)?$");
    private static final int MAX_EXPANDED_CHARS = 4_194_304;
    private final ShaderPackSource source;

    public IncludePreprocessor(ShaderPackSource source) { this.source = java.util.Objects.requireNonNull(source); }

    public ExpandedSource expand(String entry) throws IOException {
        StringBuilder output = new StringBuilder();
        Map<String, Integer> ids = new LinkedHashMap<>();
        append(ShaderPackSource.normalize(entry), output, ids, new ArrayDeque<>());
        return new ExpandedSource(output.toString(), Map.copyOf(ids));
    }

    private void append(String path, StringBuilder output, Map<String, Integer> ids,
                        ArrayDeque<String> stack) throws IOException {
        if (stack.size() >= 32 || stack.contains(path)) throw new IOException("Include cycle/depth limit: " + stack + " -> " + path);
        int id = ids.computeIfAbsent(path, ignored -> ids.size());
        stack.addLast(path);
        try {
            String[] lines = source.read(path).split("\\R", -1);
            for (int line = 0; line < lines.length; line++) {
                Matcher include = INCLUDE.matcher(lines[line]);
                if (include.matches()) {
                    String included = include.group(1);
                    if (!included.startsWith("/")) {
                        Path parent = Path.of(path).getParent();
                        included = parent == null ? included : parent + "/" + included;
                    }
                    String child = ShaderPackSource.normalize(included);
                    int childId = ids.computeIfAbsent(child, ignored -> ids.size());
                    add(output, "#line 1 " + childId + "\n");
                    append(child, output, ids, stack);
                    add(output, "#line " + (line + 2) + " " + id + "\n");
                } else {
                    if (stack.size() > 1 && lines[line].trim().startsWith("#version")) {
                        throw new IOException("#version is forbidden in included source: " + path);
                    }
                    if (lines[line].trim().startsWith("#include")) throw new IOException("Invalid include: " + path + ":" + (line + 1));
                    add(output, lines[line] + "\n");
                }
            }
        } finally { stack.removeLast(); }
    }

    private static void add(StringBuilder output, String text) throws IOException {
        if ((long) output.length() + text.length() > MAX_EXPANDED_CHARS) throw new IOException("Expanded shader too large");
        output.append(text);
    }

    public record ExpandedSource(String text, Map<String, Integer> sourceIds) {}
}
