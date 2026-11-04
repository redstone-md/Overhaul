package dev.overhaul.shader.pack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers {@code uniform T name = value;}, which GLSL allows and uniform blocks do not. A name this
 * pipeline fills keeps its uniform; any other name has nothing to supply it, so the default is its value.
 */
final class PackUniformDefaults {
    private static final Pattern DEFAULTED = Pattern.compile("(?m)^[ \\t]*uniform\\s+(\\w+)\\s+(\\w+)\\s*=\\s*([^;]+);");

    private PackUniformDefaults() {}

    static String lower(String source) {
        return DEFAULTED.matcher(source).replaceAll(match -> {
            String type = match.group(1), name = match.group(2);
            String lowered = PackInterfaceCatalog.uniform(name).isPresent() ? "uniform "+type+" "+name+";" : "const "+type+" "+name+" = "+match.group(3)+";";
            return Matcher.quoteReplacement(lowered);
        });
    }
}
