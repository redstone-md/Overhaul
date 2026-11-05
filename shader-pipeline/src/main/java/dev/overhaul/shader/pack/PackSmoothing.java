package dev.overhaul.shader.pack;

import dev.overhaul.shader.compat.UnsupportedShaderException;
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Halflives of the smoothed built-in uniforms, set by {@code const} directives in program sources.
 * The unit is a tenth of a second, which is how Iris reads them; the defaults are its defaults.
 */
public record PackSmoothing(float wetness, float dryness, float eyeBrightness, float centerDepth) {
    private static final Pattern DIRECTIVE = Pattern.compile("\\bconst\\s+(?:int|float)\\s+(wetnessHalflife|drynessHalflife|eyeBrightnessHalflife|centerDepthHalflife)\\s*=\\s*([-+0-9.eE]+)[fF]?\\s*;");
    private static final float SECONDS_PER_UNIT = 0.1f;

    public static PackSmoothing scan(Collection<String> sources) {
        float wetness = 600, dryness = 200, eyeBrightness = 10, centerDepth = 1;
        for (String source : sources) {
            var matcher = DIRECTIVE.matcher(source);
            while (matcher.find()) {
                float value = parse(matcher.group(1),matcher.group(2));
                switch (matcher.group(1)) {
                    case "wetnessHalflife" -> wetness = value;
                    case "drynessHalflife" -> dryness = value;
                    case "eyeBrightnessHalflife" -> eyeBrightness = value;
                    default -> centerDepth = value;
                }
            }
        }
        return new PackSmoothing(wetness,dryness,eyeBrightness,centerDepth);
    }
    private static float parse(String name, String text) {
        try {
            float value = PackLiterals.decimal(text);
            if (!Float.isFinite(value) || value < 0) throw new UnsupportedShaderException("Negative halflife: "+name);
            return value;
        } catch (NumberFormatException invalid) {
            throw new UnsupportedShaderException("Expected literal halflife: "+name);
        }
    }

    /** Moves {@code current} toward {@code target} across a frame; a zero halflife follows immediately. */
    public static float step(float current, float target, float halflife, float frameSeconds) {
        if (halflife <= 0) return target;
        return current+(target-current)*(1-(float)Math.exp(-Math.log(2)/(halflife*SECONDS_PER_UNIT)*frameSeconds));
    }
}
