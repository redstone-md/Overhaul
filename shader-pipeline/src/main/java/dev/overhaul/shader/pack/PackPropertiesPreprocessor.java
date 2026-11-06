package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Line-based conditionals for shaders.properties. Inactive lines are blanked, so only the
 * selected branch reaches {@link java.util.Properties}, which would otherwise read every
 * directive as a comment and merge all branches. Macros are expanded only inside image and buffer
 * declarations, whose sizes packs write as option names, as Iris's preprocessor does.
 */
final class PackPropertiesPreprocessor {
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(define|undef|ifdef|ifndef|if|elif|else|endif)\\b(.*)$");
    private static final Pattern DEFINITION = Pattern.compile("([A-Za-z_]\\w*)(\\(?)\\s*(.*)");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_]\\w*");
    private static final int MAX_NESTING = 64;
    private static final Pattern SIZED = Pattern.compile("^(\\s*(?:image\\.\\w+|bufferObject\\.\\d+)\\s*[=:])(.*)$");
    private static final Pattern WORD = Pattern.compile("\\b[A-Za-z_]\\w*\\b");

    private PackPropertiesPreprocessor() {}

    private static final class Branch {
        final boolean parentActive;
        boolean taken, active, sawElse;
        Branch(boolean parentActive, boolean condition) {
            this.parentActive = parentActive; active = parentActive && condition; taken = active;
        }
        void alternate(boolean condition) {
            active = parentActive && !taken && condition; taken |= active;
        }
    }

    static String process(String source, Map<String,String> initialDefines) {
        Map<String,String> defines = new HashMap<>(initialDefines);
        Deque<Branch> branches = new ArrayDeque<>();
        StringBuilder output = new StringBuilder(source.length());
        boolean continued = false;
        for (String line : source.split("\\R",-1)) {
            var directive = continued ? null : DIRECTIVE.matcher(line);
            boolean active = branches.isEmpty() || branches.peek().active;
            if (directive != null && directive.matches()) {
                apply(directive.group(1),strip(directive.group(2)),defines,branches,active);
                continued = false;
            } else {
                if (active) output.append(continued ? line : expand(line,defines));
                continued = continues(line,continued);
            }
            output.append('\n');
        }
        if (!branches.isEmpty()) throw invalid("Unterminated conditional in shaders.properties");
        return output.toString();
    }

    private static void apply(String kind, String argument, Map<String,String> defines, Deque<Branch> branches, boolean active) {
        switch (kind) {
            case "define" -> { if (active) define(argument,defines); }
            case "undef" -> { if (active) defines.remove(name(kind,argument)); }
            case "ifdef" -> push(branches,active,active && defines.containsKey(name(kind,argument)));
            case "ifndef" -> push(branches,active,active && !defines.containsKey(name(kind,argument)));
            case "if" -> push(branches,active,active && PackMacroExpression.evaluate(argument,defines));
            case "elif" -> {
                Branch branch = current(kind,branches);
                if (branch.sawElse) throw invalid("#elif follows #else in shaders.properties");
                branch.alternate(branch.parentActive && !branch.taken && PackMacroExpression.evaluate(argument,defines));
            }
            case "else" -> {
                Branch branch = current(kind,branches);
                if (branch.sawElse) throw invalid("Repeated #else in shaders.properties");
                branch.sawElse = true; branch.alternate(true);
            }
            default -> { current(kind,branches); branches.pop(); }
        }
    }

    private static void push(Deque<Branch> branches, boolean active, boolean condition) {
        if (branches.size() >= MAX_NESTING) throw invalid("Conditionals nest too deeply in shaders.properties");
        branches.push(new Branch(active,condition));
    }

    private static Branch current(String kind, Deque<Branch> branches) {
        if (branches.isEmpty()) throw invalid("#" + kind + " has no matching #if in shaders.properties");
        return branches.peek();
    }

    private static void define(String argument, Map<String,String> defines) {
        var definition = DEFINITION.matcher(argument);
        if (!definition.matches()) throw invalid("Invalid #define in shaders.properties: " + argument);
        if (!definition.group(2).isEmpty()) throw invalid("Function-like macro in shaders.properties: " + definition.group(1));
        defines.put(definition.group(1),definition.group(3).trim());
    }

    /** Replaces macro names in an image or buffer value by their definitions, nested up to a bound. */
    private static String expand(String line, Map<String,String> defines) {
        var sized = SIZED.matcher(line);
        if (!sized.matches()) return line;
        String value = sized.group(2);
        for (int depth = 0; depth < 8; depth++) {
            var word = WORD.matcher(value);
            StringBuilder result = new StringBuilder();
            boolean changed = false;
            while (word.find()) {
                String definition = defines.get(word.group());
                boolean replace = definition != null && !definition.isEmpty();
                changed |= replace;
                word.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(replace ? definition : word.group()));
            }
            word.appendTail(result);
            value = result.toString();
            if (!changed) break;
        }
        return sized.group(1)+value;
    }

    private static String name(String kind, String argument) {
        if (!NAME.matcher(argument).matches()) throw invalid("#" + kind + " requires one macro name: " + argument);
        return argument;
    }

    private static String strip(String argument) {
        String text = argument.replaceAll("/\\*.*?\\*/"," ");
        int comment = text.indexOf("//");
        return (comment < 0 ? text : text.substring(0,comment)).trim();
    }

    /** Properties comments never continue, whereas a value ending in an odd backslash run does. */
    private static boolean continues(String line, boolean alreadyContinued) {
        String trimmed = line.stripLeading();
        if (!alreadyContinued && (trimmed.startsWith("#") || trimmed.startsWith("!"))) return false;
        int slashes = 0;
        for (int index = line.length()-1; index >= 0 && line.charAt(index) == '\\'; index--) slashes++;
        return slashes % 2 == 1;
    }

    private static UnsupportedShaderException invalid(String message) { return new UnsupportedShaderException(message); }
}
