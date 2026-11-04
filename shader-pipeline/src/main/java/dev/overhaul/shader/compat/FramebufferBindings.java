package dev.overhaul.shader.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps fragment output locations to OptiFine/Iris colortex indices. Follows Iris: the last occurrence
 * of each keyword counts, the later keyword wins when both exist, and neither means colortex0.
 */
public record FramebufferBindings(List<Integer> colorTargets) {
    private static final Pattern DRAW = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9N]+)\\s*\\*/");
    private static final Pattern TARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9, ]+)\\s*\\*/");

    public FramebufferBindings {
        colorTargets = List.copyOf(colorTargets);
        if (colorTargets.size() > 8) throw new UnsupportedShaderException("At most eight simultaneous color targets are supported");
        var unique = new HashSet<Integer>();
        for (int target : colorTargets) {
            if (target < -1 || target > 15 || (target >= 0 && !unique.add(target))) {
                throw new UnsupportedShaderException("Invalid or duplicate colortex target: " + target);
            }
        }
    }

    public static FramebufferBindings parse(String fragmentSource) {
        Directive draw = last(DRAW,"DRAWBUFFERS",fragmentSource), targets = last(TARGETS,"RENDERTARGETS",fragmentSource);
        List<Integer> result = new ArrayList<>();
        if (draw == null && targets == null) result.add(0);
        else if (targets == null || (draw != null && draw.start() > targets.start())) {
            for (char value : draw.value().toCharArray()) result.add(value == 'N' ? -1 : value-'0');
        } else {
            for (String value : targets.value().split(",",-1)) {
                try { result.add(Integer.parseInt(value.trim())); }
                catch (NumberFormatException failure) { throw new UnsupportedShaderException("Invalid RENDERTARGETS directive"); }
            }
        }
        return new FramebufferBindings(result);
    }

    /** A later mention the pattern rejects would have been the directive, so it fails instead of falling back. */
    private static Directive last(Pattern pattern, String keyword, String source) {
        Matcher matcher = pattern.matcher(source);
        Directive found = null;
        while (matcher.find()) found = new Directive(matcher.start(),matcher.end(),matcher.group(1));
        int mention = source.lastIndexOf(keyword);
        if (mention >= 0 && (found == null || mention >= found.end())) throw new UnsupportedShaderException("Malformed framebuffer directive");
        return found;
    }

    private record Directive(int start, int end, String value) {}
}
