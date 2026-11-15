package dev.overhaul.minecraft.blaze;

/**
 * The compute shader that lights terrain from the surfaces {@link BlazeShadingSource} wrote. Each pixel that terrain
 * wrote holds its own colour in the colour target and its folded normal and light levels beside it; the shader lights
 * it in linear light with a coloured sun or moon, an ambient the sky tints and that the sky light level lets in, and
 * warm block light, then maps it to the screen with a filmic curve and fogs it the way the game does. Pixels terrain
 * did not write keep their colour. The traced variant finds sun shadows with one ray query per pixel near the camera
 * and fades to the sky light level's guess beyond; the plain one only guesses, so it runs on any card.
 */
final class BlazeShadingShader {
    static final int PUSH_BYTES = 128;

    private static final String HEAD = """
            #version 460
            """;
    private static final String TRACED_HEAD = """
            #extension GL_EXT_ray_query : require
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_buffer_reference_uvec2 : require
            #define TRACED
            layout(binding = 0) uniform accelerationStructureEXT scene;
            layout(binding = 3) uniform sampler2D atlas;
            """;
    // light: direction to the sun or moon, then its strength; sky: the sky colour, then daylight; size: pixels, then
    // the hit table's address; options: the fog's colour, its start's bits, the traced reach's bits, its end's bits.
    private static final String BINDINGS = """
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 1) uniform sampler2D sceneColor;
            layout(binding = 2) uniform sampler2D sceneDepth;
            layout(binding = 4, rgba8) writeonly uniform image2D lit;
            layout(binding = 5) uniform sampler2D surfaces;
            layout(push_constant) uniform Frame { mat4 inverseViewProjection; vec4 light; vec4 sky; uvec4 size; uvec4 options; };
            """;
    private static final String BODY = """
            vec3 linear(vec3 color) { return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)), step(0.04045, color)); }
            vec3 display(vec3 color) { return mix(color * 12.92, 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, color)); }

            // A filmic curve fitted to ACES: soft shoulder, slight toe.
            vec3 filmic(vec3 x) { return clamp(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }

            vec3 unfold(vec2 folded) {
                vec3 normal = vec3(folded, 1.0 - abs(folded.x) - abs(folded.y));
                float under = max(-normal.z, 0.0);
                normal.xy += vec2(normal.x >= 0.0 ? -under : under, normal.y >= 0.0 ? -under : under);
                return normalize(normal);
            }

            vec3 positionOf(ivec2 pixel) {
                float depth = texelFetch(sceneDepth, pixel, 0).r;
                vec2 uv = (vec2(pixel) + 0.5) / vec2(size.xy);
                vec4 position = inverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                return position.xyz / position.w;
            }

            // The share of the sun a surface sees: open sky makes a good guess; near the camera, rays know.
            float shadowAt(vec3 position, vec3 normal, float skyLevel, float distance) {
                float guess = smoothstep(0.8, 0.97, skyLevel);
            #ifdef TRACED
                float reach = uintBitsToFloat(options.z);
                if (distance < reach) {
                    vec3 origin = position + normal * (0.03 + distance * 0.002);
                    float traced = blocked(origin, light.xyz, 256.0) ? 0.0 : transmitted(origin, light.xyz, 256.0);
                    return mix(traced, guess, smoothstep(0.75 * reach, reach, distance));
                }
            #endif
                return guess;
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(uvec2(pixel), size.xy))) return;
                vec4 color = texelFetch(sceneColor, pixel, 0);
                vec4 surface = texelFetch(surfaces, pixel, 0);
                if (surface.a == 0.0) {
                    imageStore(lit, pixel, color);
                    return;
                }
                vec3 normal = unfold(surface.xy * 2.0 - 1.0);
                float blockLevel = surface.z, skyLevel = clamp((surface.a * 255.0 - 1.0) / 254.0, 0.0, 1.0);
                vec3 position = positionOf(pixel);
                float distance = length(position);
                float facing = max(dot(normal, light.xyz), 0.0);
                float shadow = facing > 0.0 && light.w > 0.0 ? shadowAt(position, normal, skyLevel, distance) : 0.0;
                vec3 sun = sunColor() * light.w * facing * shadow * 2.0;
                vec3 ambient = mix(vec3(1.0), skyTint(), 0.5) * brightness(skyLevel) * (0.08 + 0.5 * sky.w) * (0.75 + 0.25 * normal.y);
                vec3 torch = vec3(1.0, 0.62, 0.32) * brightness(blockLevel) * 1.6;
                vec3 shaded = linear(color.rgb) * (sun + ambient + torch + 0.012);
                vec3 result = display(filmic(shaded * 0.6));
                // The game's own fog: a cylinder around the camera, linear from start to end, in screen colour.
                float start = uintBitsToFloat(options.y), end = uintBitsToFloat(options.w);
                vec4 fog = unpackUnorm4x8(options.x);
                float around = max(length(position.xz), abs(position.y));
                float fogged = end > start ? clamp((around - start) / (end - start), 0.0, 1.0) : 0.0;
                result = mix(result, fog.rgb, fogged * fog.a);
                imageStore(lit, pixel, vec4(result, color.a));
            }
            """;

    /** The plain variant's source, or with {@code traced} the one finding sun shadows with ray queries. */
    static String source(boolean traced) {
        if (!traced) return HEAD + BINDINGS + BlazeRayShader.LIGHTS + BODY;
        return HEAD + TRACED_HEAD + BINDINGS + BlazeRayShader.BUFFERS + BlazeRayShader.SCENE + BlazeRayShader.LIGHTS + BODY;
    }

    private BlazeShadingShader() {}
}
