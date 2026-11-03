package dev.overhaul.shader.compat;

import dev.overhaul.shader.pack.ShaderPackProperties;
import java.util.Objects;
import java.util.regex.Pattern;

/** Lowers fixed alpha state after compatibility translation, including early returns from the pack entry point. */
public final class FragmentAlphaTest {
    private static final Pattern COLOR = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;");
    private static final String ENTRY = "overhaulAlphaTestEntry";
    private static final Pattern REFERENCE=Pattern.compile("\\buniform\\s+float\\s+alphaTestRef\\s*;");

    private FragmentAlphaTest() {}
    /** This value belongs to the selected material variant, not the frame-wide uniform buffer. */
    public static String lowerReference(String source,ShaderPackProperties.AlphaTest state){
        if(!REFERENCE.matcher(source).find())return source;
        float reference=state==null||!state.enabled()?0:state.reference();
        return "const float alphaTestRef="+Float.toString(reference)+";\n"+REFERENCE.matcher(source).replaceAll("");
    }

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
        String condition = "false";
        if (!state.comparison().equals("NEVER")) {
            var color = COLOR.matcher(fragment);
            if (!color.find()) throw unsupported("Fragment alpha test requires a vec4 output at location zero");
            condition = color.group(1) + ".a " + operator + " " + Float.toString(state.reference());
        }
        return ShaderMainWrapper.wrap(fragment,ENTRY,"","if (!("+condition+")) discard;");
    }

    private static UnsupportedShaderException unsupported(String message) { return new UnsupportedShaderException(message); }
}
