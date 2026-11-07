package dev.overhaul.shader.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Game draws carry no neighbour information, so a pack geometry program that reads triangles or lines with adjacency
 * cannot get it from the draw. It is rewritten to read plain triangles or lines: each primitive vertex index maps to
 * its own vertex, and each missing neighbour is the primitive mirrored across the shared edge, or the line continued
 * straight past its end. Neighbour varyings come from the nearest primitive vertex.
 */
public final class GeometryAdjacency {
    private static final Pattern LAYOUT = Pattern.compile("\\blayout\\s*\\(\\s*(triangles|lines)_adjacency\\s*((?:,[^)]*)?)\\)\\s*in\\s*;");
    private static final Pattern DECLARATION = Pattern.compile("(?m)^[ \\t]*(?:(?:flat|smooth|noperspective|centroid)\\s+)?in\\s+\\w+\\s+(\\w+)\\s*\\[\\s*\\d*\\s*]\\s*;");
    private static final String VERTEX = "ovAdjacentVertex", POSITION = "ovAdjacentPosition";
    private static final String TRIANGLES = "int " + VERTEX + "(int k){return (k&1)==0?k>>1:((k>>1)+2)%3;}\n"
            + "vec4 " + POSITION + "(int k){if((k&1)==0)return gl_in[k>>1].gl_Position;int a=k>>1;"
            + "return gl_in[a].gl_Position+gl_in[(a+1)%3].gl_Position-gl_in[(a+2)%3].gl_Position;}\n";
    private static final String LINES = "int " + VERTEX + "(int k){return clamp(k-1,0,1);}\n"
            + "vec4 " + POSITION + "(int k){if(k==1||k==2)return gl_in[k-1].gl_Position;int e=k==0?0:1;"
            + "return 2.0*gl_in[e].gl_Position-gl_in[1-e].gl_Position;}\n";

    private GeometryAdjacency() {}

    /** Returns the source unchanged unless its input primitive has adjacency. */
    public static String lower(String source) {
        Matcher layout = LAYOUT.matcher(source);
        if (!layout.find()) return source;
        boolean triangles = layout.group(1).equals("triangles");
        List<String> arrays = new ArrayList<>(List.of("gl_in"));
        List<int[]> declarations = new ArrayList<>();
        Matcher declaration = DECLARATION.matcher(source);
        while (declaration.find()) { arrays.add(declaration.group(1)); declarations.add(new int[]{declaration.start(), declaration.end()}); }
        String count = triangles ? "6" : "4";
        String rewritten = rewrite(source, Pattern.compile("\\b(" + String.join("|", arrays) + ")\\s*(\\[|\\.\\s*length\\s*\\(\\s*\\))"), declarations, count);
        Matcher moved = LAYOUT.matcher(rewritten);
        if (!moved.find()) throw new UnsupportedShaderException("Geometry adjacency layout was lost while lowering");
        String plain = "layout(" + layout.group(1) + moved.group(2) + ") in;\n" + (triangles ? TRIANGLES : LINES);
        return rewritten.substring(0, moved.start()) + plain + rewritten.substring(moved.end());
    }

    private static String rewrite(String source, Pattern access, List<int[]> skipped, String count) {
        StringBuilder result = new StringBuilder(source.length() + 256);
        Matcher matcher = access.matcher(source);
        int copied = 0, from = 0;
        while (matcher.find(from)) {
            from = matcher.end();
            if (inside(skipped, matcher.start())) continue;
            result.append(source, copied, matcher.start());
            if (!matcher.group(2).equals("[")) { result.append(count); copied = from; continue; }
            int close = closing(source, matcher.end());
            String index = rewrite(source.substring(matcher.end(), close), access, List.of(), count);
            Matcher member = Pattern.compile("\\G\\s*\\.\\s*gl_Position\\b").matcher(source).region(close + 1, source.length());
            if (matcher.group(1).equals("gl_in") && member.lookingAt()) {
                result.append(POSITION).append('(').append(index).append(')');
                copied = from = member.end();
            } else {
                result.append(matcher.group(1)).append('[').append(VERTEX).append('(').append(index).append(")]");
                copied = from = close + 1;
            }
        }
        return result.append(source, copied, source.length()).toString();
    }

    private static boolean inside(List<int[]> ranges, int at) {
        for (int[] range : ranges) if (at >= range[0] && at < range[1]) return true;
        return false;
    }

    private static int closing(String source, int open) {
        int depth = 1;
        for (int at = open; at < source.length(); at++) {
            char c = source.charAt(at);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return at;
        }
        throw new UnsupportedShaderException("Geometry input index has no closing bracket");
    }
}
