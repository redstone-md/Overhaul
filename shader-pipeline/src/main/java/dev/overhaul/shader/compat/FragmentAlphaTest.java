package dev.overhaul.shader.compat;

import dev.overhaul.shader.pack.ShaderPackProperties;
import java.util.Objects;
import java.util.regex.Pattern;

/** Lowers fixed alpha state after compatibility translation, including early returns from the pack entry point. */
public final class FragmentAlphaTest {
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final Pattern COLOR = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;");
    private static final String ENTRY = "overhaulAlphaTestEntry";

    private FragmentAlphaTest() {}

    public static String lower(String fragment, ShaderPackProperties.AlphaTest state) {
        Objects.requireNonNull(fragment, "fragment");
        if (state == null || !state.enabled()) return fragment;
        if (!Float.isFinite(state.reference()) || state.reference() < 0 || state.reference() > 1) {
            throw unsupported("Alpha reference must be finite and in [0,1]");
        }
        String operator = switch (state.comparison()) {
            case "NEVER", "ALWAYS" -> "";
            case "LESS" -> "<";
            case "EQUAL" -> "==";
            case "LEQUAL" -> "<=";
            case "GREATER" -> ">";
            case "NOTEQUAL" -> "!=";
            case "GEQUAL" -> ">=";
            default -> throw unsupported("Unknown alpha comparison: " + state.comparison());
        };
        if (state.comparison().equals("ALWAYS")) return fragment;
        if (Pattern.compile("\\b" + ENTRY + "\\b").matcher(fragment).find()) {
            throw unsupported("Reserved alpha-test entry point is already declared");
        }
        var main = MAIN.matcher(fragment);
        if (!main.find()) throw unsupported("Fragment alpha test requires a canonical main entry point");
        int start = main.start(), end = main.end();
        if (main.find()) throw unsupported("Fragment shader has multiple main definitions");
        String condition = "false";
        if (!state.comparison().equals("NEVER")) {
            var color = COLOR.matcher(fragment);
            if (!color.find()) throw unsupported("Fragment alpha test requires a vec4 output at location zero");
            condition = color.group(1) + ".a " + operator + " " + Float.toString(state.reference());
        }
        return fragment.substring(0, start) + "void " + ENTRY + "(){" + fragment.substring(end)
                + "\nvoid main(){" + ENTRY + "();if (!(" + condition + ")) discard;}\n";
    }

    private static UnsupportedShaderException unsupported(String message) { return new UnsupportedShaderException(message); }
}
