package dev.overhaul.shader.pack;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Value settings rewrite their source declaration to the chosen value. The same name also reaches the compiler as a
 * macro, and a pack line that kept its default would redefine that macro with a different body.
 */
final class PackValueOptions {
    private static final Pattern DEFINE = Pattern.compile("(?m)^([\\t ]*#[\\t ]*define[\\t ]+([A-Za-z_]\\w*)[\\t ]+)([^\\s/][^\\r\\n]*?)([\\t ]*(?://[^\\r\\n]*)?)$");
    private final Map<String,String> selected;

    PackValueOptions(Map<String,String> selected) { this.selected = Map.copyOf(selected); }

    String apply(String source) {
        if (selected.isEmpty()) return source;
        Matcher declarations = DEFINE.matcher(source);
        StringBuilder result = new StringBuilder(source.length());
        int copied = 0, scanned = 0;
        boolean block = false;
        while (declarations.find()) {
            block = PackBooleanOptions.blockBefore(source, scanned, declarations.start(), block);
            scanned = declarations.start();
            String value = selected.get(declarations.group(2));
            if (block || value == null) continue;
            result.append(source, copied, declarations.start()).append(declarations.group(1)).append(value).append(declarations.group(4));
            copied = declarations.end();
        }
        return result.append(source, copied, source.length()).toString();
    }
}
