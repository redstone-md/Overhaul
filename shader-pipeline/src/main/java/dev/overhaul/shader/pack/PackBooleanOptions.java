package dev.overhaul.shader.pack;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Boolean settings toggle their source declaration; defining a false option as zero would break #ifdef. */
final class PackBooleanOptions {
    private static final Pattern DEFINE = Pattern.compile("(?m)^([\\t ]*)(?://[\\t ]*)?#[\\t ]*define[\\t ]+([A-Za-z_]\\w*)[\\t ]*(?://[^\\r\\n]*)?$");
    private final Map<String,Boolean> selected;

    PackBooleanOptions(Map<String,Boolean> selected) { this.selected = Map.copyOf(selected); }

    String apply(String source) {
        if (selected.isEmpty()) return source;
        Matcher declarations = DEFINE.matcher(source);
        StringBuilder result = new StringBuilder(source.length());
        int copied = 0, scanned = 0;
        boolean block = false;
        while (declarations.find()) {
            block = blockBefore(source, scanned, declarations.start(), block);
            scanned = declarations.start();
            Boolean enabled = selected.get(declarations.group(2));
            if (block || enabled == null) continue;
            result.append(source, copied, declarations.start()).append(declarations.group(1));
            if (!enabled) result.append("// ");
            result.append("#define ").append(declarations.group(2));
            // Preserve option descriptions without making them part of the macro replacement text.
            String line = declarations.group();
            int define = line.indexOf('#'), description = line.indexOf("//", define);
            if (description >= 0) result.append(' ').append(line, description, line.length());
            copied = declarations.end();
        }
        return result.append(source, copied, source.length()).toString();
    }

    private static boolean blockBefore(String source, int from, int end, boolean block) {
        for (int index = from; index < end; index++) {
            char value = source.charAt(index);
            char next = index + 1 < end ? source.charAt(index + 1) : '\0';
            if (block) {
                if (value == '*' && next == '/') { block = false; index++; }
            } else if (value == '/' && next == '*') { block = true; index++; }
            else if (value == '/' && next == '/') {
                while (index < end && source.charAt(index) != '\n') index++;
            }
        }
        return block;
    }
}
