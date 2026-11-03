package dev.overhaul.shader.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Maps fragment output locations to OptiFine/Iris colortex indices. */
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
        var draw = DRAW.matcher(fragmentSource); var targets = TARGETS.matcher(fragmentSource);
        boolean hasDraw = draw.find(), hasTargets = targets.find();
        if ((!hasDraw && fragmentSource.contains("DRAWBUFFERS"))
                || (!hasTargets && fragmentSource.contains("RENDERTARGETS"))) {
            throw new UnsupportedShaderException("Malformed framebuffer directive");
        }
        if (hasDraw && hasTargets) throw new UnsupportedShaderException("Conflicting framebuffer directives");
        List<Integer> result = new ArrayList<>();
        if (hasDraw) {
            for (char value : draw.group(1).toCharArray()) result.add(value == 'N' ? -1 : value-'0');
            if (draw.find()) throw new UnsupportedShaderException("Multiple DRAWBUFFERS directives");
        } else if (hasTargets) {
            for (String value : targets.group(1).split(",",-1)) {
                try { result.add(Integer.parseInt(value.trim())); }
                catch (NumberFormatException failure) { throw new UnsupportedShaderException("Invalid RENDERTARGETS directive"); }
            }
            if (targets.find()) throw new UnsupportedShaderException("Multiple RENDERTARGETS directives");
        } else result.add(0);
        return new FramebufferBindings(result);
    }
}
