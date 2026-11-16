package dev.overhaul.minecraft.blaze;

/**
 * The compute shader that lights terrain from the surfaces {@link BlazeShadingSource} wrote. Each pixel that terrain
 * wrote holds its own colour in the colour target and its folded normal and light levels beside it; the shader lights
 * it in linear light with a coloured sun or moon, an ambient the sky tints and that the sky light level lets in, and
 * warm block light, then maps it to the screen with a filmic curve and fogs it the way the game does. Pixels terrain
 * did not write keep their colour. The traced variant finds sun shadows with one ray query per pixel near the camera
 * and fades to the sky light level's guess beyond; the plain one guesses, and can walk the depth it already has
 * toward the sun for the shadows of what is on screen, so it runs on any card. Pixels are lit two by two, sharing
 * what they found of the sun. The linear variant also leaves their mean linear light at half size, for
 * {@link BlazeShadingGlow} to blur and measure; it mixes in the glow and the exposure that made of the frame before,
 * so the whole picture is still one pass over the screen.
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
            layout(binding = 4, rgba8) writeonly uniform image2D lit;
            // Read, then wiped for the frame to come.
            layout(binding = 5, rgba8) uniform image2D surfaces;
            #ifdef LINEAR
            // The glow and the measured brightness of the frame before, and this frame's light at half size.
            layout(binding = 6) uniform sampler2D glow;
            layout(binding = 7) uniform sampler2D measured;
            layout(binding = 8, rgba16f) writeonly uniform image2D spread;
            #endif
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
                vec3 result = display(filmic(shaded * 0.5 * exposure));
                float start = uintBitsToFloat(options.y), end = uintBitsToFloat(options.w);
                vec4 fog = unpackUnorm4x8(options.x);
                float around = max(length(position.xz), abs(position.y));
                float fogged = end > start ? clamp((around - start) / (end - start), 0.0, 1.0) : 0.0;
                return mix(result, fog.rgb, fogged * fog.a);
            }
            """;
    private static final String BODY = """
            #ifndef TRACED
            // From the world around the camera back to the screen, and what gives the depth in front of the camera
            // from a place on screen; main works them out once for its four pixels.
            mat4 viewProjection;
            vec4 depthRow;

            // The share of the sun left after walking the depth on screen toward it: steps grow with the distance
            // walked and start at a place that differs per pixel, part being its quarter of a step, so the few steps
            // do not band. A step is in shadow
            // when what the screen shows there is nearer the camera than the step, by less than a block and a half: anything
            // further in front is something else passing by. Off screen nothing is known, so the walk ends lit.
            float walked(vec3 position, vec3 normal, float distance, float reach, ivec2 pixel, float part) {
                vec3 origin = position + normal * (0.02 + distance * 0.004);
                float far = reach * clamp(distance / 24.0, 0.2, 1.5);
                float offset = (part + fract(52.9829189 * fract(dot(vec2(pixel >> 1), vec2(0.06711056, 0.00583715))))) * 0.25;
                vec4 from = viewProjection * vec4(origin, 1.0), toward = viewProjection * vec4(light.xyz * far, 0.0);
                for (int i = 0; i < 8; i++) {
                    float along = (float(i) + offset) / 8.0;
                    vec4 clip = from + toward * (along * along);
                    if (clip.w <= 0.0) break;
                    vec2 place = clip.xy / clip.w, uv = place * 0.5 + 0.5;
                    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) break;
                    ivec2 at = ivec2(uv * vec2(size.xy));
                    if (at == pixel) continue;
                    float gap = clip.w - 1.0 / dot(depthRow, vec4(place, texelFetch(sceneDepth, at, 0).r * 2.0 - 1.0, 1.0));
                    float slack = 0.05 + clip.w * 0.004;
                    if (gap > slack && gap < 1.5 + slack) {
                        vec2 edge = min(uv, 1.0 - uv);
                        return 1.0 - smoothstep(0.0, 0.03, min(edge.x, edge.y));
                    }
                }
                return 1.0;
            }
            #endif

            // The share of the sun a surface sees: open sky makes a good guess; near the camera, rays know.
            float shadowAt(vec3 position, vec3 normal, float skyLevel, float distance, ivec2 pixel, float part) {
                float guess = smoothstep(0.8, 0.97, skyLevel);
                float reach = uintBitsToFloat(options.z);
            #ifdef TRACED
                if (distance < reach) {
                    vec3 origin = position + normal * (0.03 + distance * 0.002);
                    float traced = blocked(origin, light.xyz, 256.0) ? 0.0 : transmitted(origin, light.xyz, 256.0);
                    return mix(traced, guess, smoothstep(0.75 * reach, reach, distance));
                }
            #else
                if (reach > 0.0 && guess > 0.0) return guess * walked(position, normal, distance, reach, pixel, part);
            #endif
                return guess;
            }

            // What one pixel holds before it is lit; shadow is the share of the sun it found for itself.
            struct Seen { vec4 color; vec3 normal; vec3 position; float blockLevel; float skyLevel; float shadow; bool inside; bool terrain; };

            // Reads one pixel; part is its place among the four that share what they found of the sun.
            Seen seenAt(ivec2 pixel, float part) {
                Seen seen;
                seen.inside = all(lessThan(uvec2(pixel), size.xy));
                seen.terrain = false;
                seen.shadow = 0.0;
                if (!seen.inside) return seen;
                seen.color = texelFetch(sceneColor, pixel, 0);
                vec4 surface = imageLoad(surfaces, pixel);
                if (surface.a == 0.0) return seen;
                imageStore(surfaces, pixel, vec4(0.0));
                seen.terrain = true;
                seen.normal = unfold(surface.xy * 2.0 - 1.0);
                seen.blockLevel = surface.z;
                seen.skyLevel = clamp((surface.a * 255.0 - 1.0) / 254.0, 0.0, 1.0);
                seen.position = positionOf(pixel);
                if (dot(seen.normal, light.xyz) > 0.0 && light.w > 0.0)
                    seen.shadow = shadowAt(seen.position, seen.normal, seen.skyLevel, length(seen.position), pixel, part);
                return seen;
            }

            // The game's sky with the air's own light added: deeper overhead, hazy at the horizon, bright around the
            // sun or moon. Only where nothing was drawn over the sky, and not in fog too thick to see it.
            vec3 skyOf(vec3 drawn, ivec2 pixel) {
                if (light.w <= 0.0 || uintBitsToFloat(options.w) < 8.0 || texelFetch(sceneDepth, pixel, 0).r < 1.0) return drawn;
                vec3 toward = normalize(positionOf(pixel));
                float near = max(dot(toward, light.xyz), 0.0);
                float low = pow(1.0 - clamp(toward.y, 0.0, 1.0), 5.0) * smoothstep(-0.2, 0.0, toward.y);
                vec3 air = linear(drawn) * mix(1.0, 0.8, smoothstep(0.1, 0.9, toward.y));
                vec3 glow = sunColor() * light.w * (0.18 * pow(near, 6.0) + 0.5 * pow(near, 96.0) + 0.12 * low * (0.3 + 0.7 * near));
                return display(1.0 - (1.0 - air) * (1.0 - clamp(glow, 0.0, 1.0)));
            }

            // Lights one pixel onto the screen with its share of the sun; gives its linear light back, with 1 beside
            // it where terrain is.
            vec4 lightOf(Seen seen, float shadow, ivec2 pixel) {
                if (!seen.inside) return vec4(0.0);
                if (!seen.terrain) {
                    imageStore(lit, pixel, vec4(skyOf(seen.color.rgb, pixel), seen.color.a));
                    return vec4(0.0);
                }
                vec3 normal = seen.normal;
                // A little warmer than the sun itself and a little bluer than the sky, the way film sees daylight.
                vec3 sun = sunColor() * vec3(1.04, 0.97, 0.86) * light.w * max(dot(normal, light.xyz), 0.0) * shadow * 2.0;
                vec3 ambient = mix(vec3(1.0), skyTint(), 0.65) * brightness(seen.skyLevel) * (0.08 + 0.5 * sky.w) * (0.75 + 0.25 * normal.y);
                vec3 torch = vec3(1.0, 0.62, 0.32) * brightness(seen.blockLevel) * 1.6;
                vec3 shaded = linear(seen.color.rgb) * (sun + ambient + torch + 0.012);
                vec3 shown = shaded;
                float exposure = 1.0;
            #ifdef LINEAR
                // An empty glow or measure is one switched off, or the first frame's.
                vec4 around = textureLod(glow, (vec2(pixel) + 0.5) / vec2(size.xy), 0.0);
                if (around.a > 0.0) shown = mix(shaded, around.rgb / around.a, 0.12);
                float average = texelFetch(measured, ivec2(0), 0).r;
                if (average > 0.0) exposure = clamp(sqrt(0.2 / average), 0.5, 2.0);
            #endif
                imageStore(lit, pixel, vec4(screened(shown, seen.position, exposure), seen.color.a));
                return vec4(shaded, 1.0);
            }

            // Two by two pixels at a time: each finds its share of the sun at its own place along the way, and those
            // on one surface share the mean, which is as good as four times the steps.
            void main() {
                ivec2 cell = ivec2(gl_GlobalInvocationID.xy);
            #ifndef TRACED
                if (uintBitsToFloat(options.z) > 0.0) {
                    viewProjection = inverse(inverseViewProjection);
                    depthRow = vec4(inverseViewProjection[0][3], inverseViewProjection[1][3], inverseViewProjection[2][3], inverseViewProjection[3][3]);
                }
            #endif
                Seen seen[4];
                for (int i = 0; i < 4; i++) seen[i] = seenAt(cell * 2 + ivec2(i & 1, i >> 1), float(i));
                vec4 sum = vec4(0.0);
                for (int i = 0; i < 4; i++) {
                    float shadow = 0.0, weight = 0.0, distance = length(seen[i].position);
                    if (seen[i].terrain) for (int j = 0; j < 4; j++) {
                        if (!seen[j].terrain || dot(seen[i].normal, seen[j].normal) < 0.9) continue;
                        if (abs(length(seen[j].position) - distance) > 0.1 + 0.02 * distance) continue;
                        shadow += seen[j].shadow;
                        weight += 1.0;
                    }
                    sum += lightOf(seen[i], weight > 0.0 ? shadow / weight : 0.0, cell * 2 + ivec2(i & 1, i >> 1));
                }
            #ifdef LINEAR
                if (all(lessThan(cell, imageSize(spread)))) imageStore(spread, cell, sum * 0.25);
            #endif
            }
            """;

    /**
     * The plain variant's source, or with {@code traced} the one finding sun shadows with ray queries; with
     * {@code linear} it also leaves linear light at half size and shows the glow and exposure made of the frame before.
     */
    static String source(boolean traced, boolean linear) {
        String head = HEAD + (linear ? "#define LINEAR\n" : "");
        if (!traced) return head + BINDINGS + BlazeRayShader.LIGHTS + SCREEN + BODY;
        return head + TRACED_HEAD + BINDINGS + BlazeRayShader.BUFFERS + BlazeRayShader.SCENE + BlazeRayShader.LIGHTS + SCREEN + BODY;
    }

    private BlazeShadingShader() {}
}
