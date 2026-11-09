package dev.overhaul.minecraft.blaze;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenGL sampler objects: a name bound to a texture unit overrides the sampling state of whatever texture that unit
 * holds, as Veil and Distant Horizons expect. Names come from glGenSamplers and take state on first use.
 */
final class BlazeSamplerObjects {
    private final int[] bindings;
    private final Map<Integer, BlazeSamplerState> states = new HashMap<>();

    BlazeSamplerObjects(int units) {
        bindings = new int[units];
    }

    void bind(int unit, int sampler) {
        if (unit < 0 || unit >= bindings.length) throw new IllegalArgumentException("Sampler unit outside owned range");
        if (sampler != 0) state(sampler);
        bindings[unit] = sampler;
    }

    /** A deleted name leaves every unit that held it, as glDeleteSamplers does. */
    void delete(int sampler) {
        if (sampler == 0 || states.remove(sampler) == null) return;
        for (int unit = 0; unit < bindings.length; unit++) if (bindings[unit] == sampler) bindings[unit] = 0;
    }

    void parameter(int sampler, int name, float value) {
        if (!BlazeSamplerState.owns(name)) {
            BlazeGlBoundary.reportOnce("sampler parameter " + name, "Sampler parameter has no Vulkan sampler state and was ignored: " + name);
            return;
        }
        state(sampler).set(name, value);
    }

    void border(int sampler, float r, float g, float b, float a) {
        state(sampler).border(r, g, b, a);
    }

    int bound(int unit) {
        return unit < 0 || unit >= bindings.length ? 0 : bindings[unit];
    }

    /** The state that overrides the unit's texture, or null when the unit has no sampler object. */
    BlazeSamplerState override(int unit) {
        int sampler = bound(unit);
        return sampler == 0 ? null : states.get(sampler);
    }

    private BlazeSamplerState state(int sampler) {
        if (sampler <= 0) throw new IllegalArgumentException("Invalid sampler name: " + sampler);
        return states.computeIfAbsent(sampler, ignored -> new BlazeSamplerState());
    }
}
