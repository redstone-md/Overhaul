package dev.overhaul.minecraft.blaze;

/**
 * The compute shader {@link BlazeShadingGlow} runs over the linear light {@link BlazeShadingShader} left at half
 * size: it halves a level into the next, doubles a level back over the one above it, or measures the smallest
 * level's brightness. Light is stored with the share of terrain in its alpha, so levels blur terrain only and the
 * sky neither glows nor darkens the edges.
 */
final class BlazeShadingGlowShader {
    static final int STEP_BYTES = 16, HALVE = 0, DOUBLE = 1, MEASURE = 2;

    // size: the written level's pixels; blend: how much of the new value replaces what the target holds.
    static final String STEP = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D source;
            layout(binding = 1, rgba16f) uniform image2D target;
            layout(push_constant) uniform Step { ivec2 size; int mode; float blend; };

            vec4 around(vec2 uv, vec2 reach) {
                return (textureLod(source, uv + vec2(-reach.x, -reach.y), 0.0) + textureLod(source, uv + vec2(reach.x, -reach.y), 0.0)
                        + textureLod(source, uv + vec2(-reach.x, reach.y), 0.0) + textureLod(source, uv + reach, 0.0)) * 0.25;
            }

            // The brightness the eye would settle on: the mean of the logarithm, over terrain only.
            void measure() {
                ivec2 extent = textureSize(source, 0);
                float sum = 0.0, weight = 0.0;
                for (int y = 0; y < extent.y; y++) for (int x = 0; x < extent.x; x++) {
                    vec4 light = texelFetch(source, ivec2(x, y), 0);
                    if (light.a <= 0.0) continue;
                    sum += light.a * log(max(dot(light.rgb / light.a, vec3(0.2126, 0.7152, 0.0722)), 1e-4));
                    weight += light.a;
                }
                float before = imageLoad(target, ivec2(0)).r;
                float now = weight > 0.0 ? exp(sum / weight) : before;
                imageStore(target, ivec2(0), vec4(before > 0.0 ? mix(before, now, blend) : now));
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                if (mode == 2) {
                    if (pixel == ivec2(0)) measure();
                    return;
                }
                if (any(greaterThanEqual(pixel, size))) return;
                vec2 uv = (vec2(pixel) + 0.5) / vec2(size);
                if (mode == 0) imageStore(target, pixel, around(uv, 0.5 / vec2(size)));
                else imageStore(target, pixel, mix(imageLoad(target, pixel), around(uv, 1.0 / vec2(size)), blend));
            }
            """;

    private BlazeShadingGlowShader() {}
}
