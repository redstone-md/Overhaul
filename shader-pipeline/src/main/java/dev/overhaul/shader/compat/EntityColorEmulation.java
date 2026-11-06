package dev.overhaul.shader.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris's {@code entityColor}: the hurt and white flash tint an entity's overlay texture would apply, as colour and
 * strength. The game batches entities into shared draws, so one uniform cannot tell them apart; like Iris, the vertex
 * stage derives it from each vertex's overlay coordinates and hands it on as a varying.
 */
public final class EntityColorEmulation {
    /** Overlay texture coordinates (game UV1); a mesh without them reads the no-overlay texel. */
    public static final String OVERLAY = "overhaulEntityColorOverlay";
    private static final Pattern UNIFORM = Pattern.compile("\\buniform\\s+vec4\\s+entityColor\\s*;");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\r\\n]*\\R?");
    // OverlayTexture: rows below 8 are red at 178/255 opacity of the base colour; the rest fade to white with the column.
    private static final String VALUE = "entityColor=" + OVERLAY + ".y<8.0?vec4(1.0,0.0,0.0,77.0/255.0):vec4(1.0,1.0,1.0," + OVERLAY + ".x/15.0*0.75);";

    private EntityColorEmulation() {}

    public static boolean needed(String fragment) {
        return UNIFORM.matcher(fragment).find();
    }

    public static String fragment(String source) {
        return UNIFORM.matcher(source).replaceAll("varying vec4 entityColor;");
    }

    public static String vertex(String source) {
        var main = MAIN.matcher(source);
        if (!main.find()) throw new UnsupportedShaderException("entityColor needs a canonical vertex main entry point");
        String body = source.substring(0, main.end()) + VALUE + source.substring(main.end());
        String declarations = "attribute vec2 " + OVERLAY + ";\n" + (UNIFORM.matcher(body).find() ? "" : "varying vec4 entityColor;\n");
        body = UNIFORM.matcher(body).replaceAll(Matcher.quoteReplacement("varying vec4 entityColor;"));
        var version = VERSION.matcher(body);
        int at = version.find() ? version.end() : 0;
        return body.substring(0, at) + declarations + body.substring(at);
    }
}
