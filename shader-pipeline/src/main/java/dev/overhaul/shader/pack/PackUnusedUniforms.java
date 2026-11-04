package dev.overhaul.shader.pack;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes uniforms a stage declares and never names. Packs include one shared list of every uniform they
 * might read, and a uniform block keeps all its members active, so without this a value no code reads
 * would still have to be supplied. Only single declarations on their own line are removed.
 */
final class PackUnusedUniforms {
    private static final Pattern DECLARATION = Pattern.compile("(?m)^[ \\t]*uniform\\s+\\w+\\s+(\\w+)\\s*;");
    private static final Pattern WORD = Pattern.compile("\\b[A-Za-z_]\\w*\\b");

    private PackUnusedUniforms() {}

    static String strip(String source) {
        Map<String,Integer> uses = new HashMap<>();
        for (Matcher word = WORD.matcher(source); word.find();) uses.merge(word.group(),1,Integer::sum);
        return DECLARATION.matcher(source).replaceAll(match -> uses.get(match.group(1)) == 1 ? "" : Matcher.quoteReplacement(match.group()));
    }
}
