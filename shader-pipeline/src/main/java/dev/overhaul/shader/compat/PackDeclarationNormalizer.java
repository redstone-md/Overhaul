package dev.overhaul.shader.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level rewrites that let common pack GLSL reach the translator's declaration subset:
 * multi-declarations, GLSL 130 {@code in}/{@code out}, and the fixed-function varyings
 * gl_FrontColor and gl_TexCoord, by constant or computed index. Interface arrays and blocks stay rejected downstream.
 */
final class PackDeclarationNormalizer {
    private static final String QUALIFIER = "(?:flat|smooth|noperspective|centroid)";
    private static final Pattern MULTI = Pattern.compile("\\b((?:"+QUALIFIER+"\\s+)?(?:uniform|attribute|varying))\\s+(\\w+)\\s+(\\w+(?:\\s*,\\s*\\w+)+)\\s*;");
    private static final Pattern MULTI_CORE = Pattern.compile("(?m)^[ \\t]*((?:"+QUALIFIER+"\\s+)?(?:in|out))\\s+(\\w+)\\s+(\\w+(?:\\s*,\\s*\\w+)+)\\s*;");
    private static final Pattern CORE_INTERFACE = Pattern.compile("(?m)^[ \\t]*("+QUALIFIER+"\\s+)?(in|out)\\s+(float|vec[234]|u?int|[iu]vec[234]|mat[234])\\s+(\\w+)\\s*;");
    private static final Pattern TEX_COORD = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*([0-7])\\s*]");
    private static final Pattern RESERVED = Pattern.compile("\\boverhaulVarying\\w*");
    private static final Pattern FRONT_COLOR = Pattern.compile("\\bgl_FrontColor\\b");
    private static final Pattern FRAGMENT_COLOR = Pattern.compile("\\bgl_Color\\b");
    private static final String FRONT_COLOR_VARYING = "overhaulVaryingFrontColor";
    private static final String TEX_COORD_VARYING = "overhaulVaryingTexCoord";
    private static final Pattern ANY_TEX_COORD = Pattern.compile("\\bgl_TexCoord\\s*\\[");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final String TEX_COORD_ARRAY = "overhaulVaryingTexCoords", TEX_COORD_MAIN = "overhaulVaryingMain";
    private static final int TEX_COORDS = 8;

    private PackDeclarationNormalizer() {}

    record Stages(String vertex, String fragment) {}

    /** Applies to every convention, because each rewrite only reaches the grammar the translator already owns. */
    static Stages declarations(String vertex, String fragment) {
        return new Stages(lowerCore(expand(vertex),true),lowerCore(expand(fragment),false));
    }

    /** Fixed-function varyings exist only for legacy conventions; run before their attribute lowering. */
    static Stages legacyVaryings(String vertex, String fragment) {
        if (RESERVED.matcher(vertex).find() || RESERVED.matcher(fragment).find()) {
            throw new UnsupportedShaderException("Shader uses reserved legacy compatibility identifiers");
        }
        String vs = vertex, fs = fragment;
        if (FRONT_COLOR.matcher(vs).find()) {
            vs = "varying vec4 "+FRONT_COLOR_VARYING+";\n"+FRONT_COLOR.matcher(vs).replaceAll(FRONT_COLOR_VARYING);
            if (FRAGMENT_COLOR.matcher(fs).find()) fs = "varying vec4 "+FRONT_COLOR_VARYING+";\n"+FRAGMENT_COLOR.matcher(fs).replaceAll(FRONT_COLOR_VARYING);
        }
        if (!computedTexCoord(vs) && !computedTexCoord(fs)) return new Stages(lowerTexCoords(vs),lowerTexCoords(fs));
        TreeSet<Integer> written = computedTexCoord(vs) ? allTexCoords() : texCoordsUsed(vs);
        return new Stages(computedTexCoords(vs,written,true),computedTexCoord(fs) ? computedTexCoords(fs,written,false) : lowerTexCoords(fs));
    }

    private static boolean computedTexCoord(String source) {
        Matcher any = ANY_TEX_COORD.matcher(source);
        while (any.find()) if (!TEX_COORD.matcher(source).region(any.start(),source.length()).lookingAt()) return true;
        return false;
    }

    private static TreeSet<Integer> allTexCoords() {
        TreeSet<Integer> all = new TreeSet<>();
        for (int index = 0; index < TEX_COORDS; index++) all.add(index);
        return all;
    }

    private static TreeSet<Integer> texCoordsUsed(String source) {
        Matcher matcher = TEX_COORD.matcher(source);
        TreeSet<Integer> used = new TreeSet<>();
        while (matcher.find()) used.add(Integer.parseInt(matcher.group(1)));
        return used;
    }

    /**
     * A computed index needs a real array, so the stage works on a private one: the vertex stage copies it into the
     * varyings once its main returns, the fragment stage fills it from the varyings the vertex stage writes before its
     * main runs, and every other set reads as zero.
     */
    private static String computedTexCoords(String source, TreeSet<Integer> varyings, boolean vertex) {
        if (!MAIN.matcher(source).find()) throw new UnsupportedShaderException("Shader indexes gl_TexCoord but has no main");
        StringBuilder head = new StringBuilder("vec4 "+TEX_COORD_ARRAY+"["+TEX_COORDS+"];\n");
        StringBuilder copy = new StringBuilder();
        for (int index : varyings) {
            head.append("varying vec4 ").append(TEX_COORD_VARYING).append(index).append(";\n");
            if (vertex) copy.append("    ").append(TEX_COORD_VARYING).append(index).append(" = ").append(TEX_COORD_ARRAY).append('[').append(index).append("];\n");
            else copy.append("    ").append(TEX_COORD_ARRAY).append('[').append(index).append("] = ").append(TEX_COORD_VARYING).append(index).append(";\n");
        }
        String body = MAIN.matcher(ANY_TEX_COORD.matcher(source).replaceAll(TEX_COORD_ARRAY+"[")).replaceFirst("void "+TEX_COORD_MAIN+"()");
        String main = vertex ? "void main() {\n    "+TEX_COORD_MAIN+"();\n"+copy+"}\n"
                : "void main() {\n    for (int i = 0; i < "+TEX_COORDS+"; i++) "+TEX_COORD_ARRAY+"[i] = vec4(0.0);\n"+copy+"    "+TEX_COORD_MAIN+"();\n}\n";
        return head+body+"\n"+main;
    }

    private static String expand(String source) { return expand(expand(source,MULTI),MULTI_CORE); }
    private static String expand(String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            List<String> declarations = new ArrayList<>();
            for (String name : matcher.group(3).split(",")) declarations.add(matcher.group(1)+" "+matcher.group(2)+" "+name.trim()+";");
            // One per line, because core declarations are recognized only at the start of a line.
            matcher.appendReplacement(result,Matcher.quoteReplacement(String.join("\n",declarations)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Only line-anchored declarations of interface types count, so function parameters keep their
     * qualifiers. One unqualified fragment output becomes location 0; with several, the location
     * is the pack's to state and compilation reports the omission.
     */
    private static String lowerCore(String source, boolean vertex) {
        List<String> outputs = new ArrayList<>();
        Matcher matcher = CORE_INTERFACE.matcher(source);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String qualifier = matcher.group(1) == null ? "" : matcher.group(1).trim()+" ", direction = matcher.group(2), type = matcher.group(3), name = matcher.group(4);
            String replacement;
            if (vertex) replacement = direction.equals("in") ? "attribute "+type+" "+name+";" : qualifier+"varying "+type+" "+name+";";
            else if (direction.equals("in")) replacement = qualifier+"varying "+type+" "+name+";";
            else { outputs.add(name); replacement = matcher.group(); }
            matcher.appendReplacement(result,Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        if (vertex || outputs.size() != 1) return result.toString();
        return Pattern.compile("(?m)^[ \\t]*out\\s+(\\w+)\\s+"+Pattern.quote(outputs.get(0))+"\\s*;")
                .matcher(result).replaceFirst("layout(location=0) out $1 "+Matcher.quoteReplacement(outputs.get(0))+";");
    }

    /** A fragment read of a set the vertex stage never writes fails the translator's varying match. */
    private static String lowerTexCoords(String source) {
        Matcher matcher = TEX_COORD.matcher(source);
        StringBuilder declarations = new StringBuilder();
        for (int index : texCoordsUsed(source)) declarations.append("varying vec4 ").append(TEX_COORD_VARYING).append(index).append(";\n");
        return declarations+matcher.replaceAll(TEX_COORD_VARYING+"$1");
    }
}
