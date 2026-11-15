package dev.overhaul.minecraft.blaze;

/**
 * The compute shader that lights terrain from the surfaces {@link BlazeShadingSource} wrote. Each pixel that terrain
 * wrote holds its own colour in the colour target and its folded normal and light levels beside it; the shader lights
 * it in linear light with a coloured sun or moon, an ambient the sky tints and that the sky light level lets in, and
 * warm block light, then maps it to the screen with a filmic curve and fogs it the way the game does. Pixels terrain
 * did not write keep their colour. The traced variant finds sun shadows with one ray query per pixel near the camera
 * and fades to the sky light level's guess beyond; the plain one guesses, and can walk the depth it already has
 * toward the sun for the shadows of what is on screen, so it runs on any card. The lit
 * variant stops before the screen: it writes the linear light for {@link BlazeShadingGlow} to finish.
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
    // the hit table's address; options: the fog's colour, its start's bits, the shadow reach's bits, its end's bits.
    // The reach is how far rays are traced in the traced variant, how far depth is walked in the plain one, 0 for none.
    static final String FRAME = """
            layout(push_constant) uniform Frame { mat4 inverseViewProjection; vec4 light; vec4 sky; uvec4 size; uvec4 options; };
            """;
    private static final String BINDINGS = """
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 1) uniform sampler2D sceneColor;
            layout(binding = 2) uniform sampler2D sceneDepth;
            #ifdef LINEAR
            layout(binding = 4, rgba16f) writeonly uniform image2D lit;
            #else
            layout(binding = 4, rgba8) writeonly uniform image2D lit;
            #endif
            layout(binding = 5) uniform sampler2D surfaces;
            """ + FRAME;
    /** From linear light to the screen: the filmic curve, then the game's fog; needs {@link #FRAME} and {@code sceneDepth}. */
    static final String SCREEN = """
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

            // Linear light at an exposure to screen colour, under the game's own fog: a cylinder around the camera,
            // linear from start to end.
            vec3 screened(vec3 shaded, vec3 position, float exposure) {
                vec3 result = display(filmic(shaded * 0.6 * exposure));
                float start = uintBitsToFloat(options.y), end = uintBitsToFloat(options.w);
                vec4 fog = unpackUnorm4x8(options.x);
                float around = max(length(position.xz), abs(position.y));
                float fogged = end > start ? clamp((around - start) / (end - start), 0.0, 1.0) : 0.0;
                return mix(result, fog.rgb, fogged * fog.a);
            }
            """;
    private static final String BODY = """
            #ifndef TRACED
            // The share of the sun left after walking the depth on screen toward it: steps grow with the distance
            // walked and start at a place that differs per pixel, so the few steps do not band. A step is in shadow
            // when what the screen shows there is nearer than the step, by less than a block and a half: anything
            // further in front is something else passing by. Off screen nothing is known, so the walk ends lit.
            float walked(vec3 position, vec3 normal, float distance, float reach, ivec2 pixel) {
                mat4 viewProjection = inverse(inverseViewProjection);
                vec3 origin = position + normal * (0.02 + distance * 0.004);
                float far = reach * clamp(distance / 24.0, 0.2, 1.5);
                float offset = fract(52.9829189 * fract(dot(vec2(pixel), vec2(0.06711056, 0.00583715))));
                for (int i = 0; i < 14; i++) {
                    float along = (float(i) + offset) / 14.0;
                    vec3 point = origin + light.xyz * (far * along * along);
                    vec4 clip = viewProjection * vec4(point, 1.0);
                    if (clip.w <= 0.0) break;
                    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
                    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) break;
                    ivec2 at = ivec2(uv * vec2(size.xy));
                    if (at == pixel) continue;
                    float gap = length(point) - length(positionOf(at));
                    float slack = 0.05 + length(point) * 0.004;
                    if (gap > slack && gap < 1.5 + slack) {
                        vec2 edge = min(uv, 1.0 - uv);
                        return 1.0 - smoothstep(0.0, 0.03, min(edge.x, edge.y));
                    }
                }
                return 1.0;
            }
            #endif

            // The share of the sun a surface sees: open sky makes a good guess; near the camera, rays know.
            float shadowAt(vec3 position, vec3 normal, float skyLevel, float distance, ivec2 pixel) {
                float guess = smoothstep(0.8, 0.97, skyLevel);
                float reach = uintBitsToFloat(options.z);
            #ifdef TRACED
                if (distance < reach) {
                    vec3 origin = position + normal * (0.03 + distance * 0.002);
                    float traced = blocked(origin, light.xyz, 256.0) ? 0.0 : transmitted(origin, light.xyz, 256.0);
                    return mix(traced, guess, smoothstep(0.75 * reach, reach, distance));
                }
            #else
                if (reach > 0.0 && guess > 0.0) return guess * walked(position, normal, distance, reach, pixel);
            #endif
                return guess;
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(uvec2(pixel), size.xy))) return;
                vec4 color = texelFetch(sceneColor, pixel, 0);
                vec4 surface = texelFetch(surfaces, pixel, 0);
                if (surface.a == 0.0) {
            #ifdef LINEAR
                    imageStore(lit, pixel, vec4(0.0));
            #else
                    imageStore(lit, pixel, color);
            #endif
                    return;
                }
                vec3 normal = unfold(surface.xy * 2.0 - 1.0);
                float blockLevel = surface.z, skyLevel = clamp((surface.a * 255.0 - 1.0) / 254.0, 0.0, 1.0);
                vec3 position = positionOf(pixel);
                float distance = length(position);
                float facing = max(dot(normal, light.xyz), 0.0);
                float shadow = facing > 0.0 && light.w > 0.0 ? shadowAt(position, normal, skyLevel, distance, pixel) : 0.0;
                vec3 sun = sunColor() * light.w * facing * shadow * 2.0;
                vec3 ambient = mix(vec3(1.0), skyTint(), 0.5) * brightness(skyLevel) * (0.08 + 0.5 * sky.w) * (0.75 + 0.25 * normal.y);
                vec3 torch = vec3(1.0, 0.62, 0.32) * brightness(blockLevel) * 1.6;
                vec3 shaded = linear(color.rgb) * (sun + ambient + torch + 0.012);
            #ifdef LINEAR
                imageStore(lit, pixel, vec4(shaded, 1.0));
            #else
                imageStore(lit, pixel, vec4(screened(shaded, position, 1.0), color.a));
            #endif
            }
            """;

    /**
     * The plain variant's source, or with {@code traced} the one finding sun shadows with ray queries; with
     * {@code linear} it writes linear light and a mark where terrain is, instead of the finished screen colour.
     */
    static String source(boolean traced, boolean linear) {
        String head = HEAD + (linear ? "#define LINEAR\n" : "");
        if (!traced) return head + BINDINGS + BlazeRayShader.LIGHTS + SCREEN + BODY;
        return head + TRACED_HEAD + BINDINGS + BlazeRayShader.BUFFERS + BlazeRayShader.SCENE + BlazeRayShader.LIGHTS + SCREEN + BODY;
    }

    private BlazeShadingShader() {}
}
