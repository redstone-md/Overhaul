package dev.overhaul.minecraft.blaze;

/**
 * The compute shader that lights terrain from the surfaces {@link BlazeShadingSource} wrote. Each pixel that terrain
 * wrote holds its own colour in the colour target and its folded normal and light levels beside it; the shader lights
 * it in linear light with a coloured sun or moon, an ambient the sky tints and that the sky light level lets in, and
 * warm block light, then maps it to the screen with a filmic curve and fogs it the way the game does. Pixels terrain
 * did not write keep their colour. The traced variant finds sun shadows with one ray query per pixel near the camera,
 * aimed across the sun's disc so their edges are soft, and fades to the sky light level's guess beyond; the plain one guesses, and can walk the depth it already has
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
    /** Between the colours the screen shows and linear light, and the filmic curve that brings light back to the screen. */
    static final String TONE = """
            vec3 linear(vec3 color) { return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)), step(0.04045, color)); }
            vec3 display(vec3 color) { return mix(color * 12.92, 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, color)); }

            // A filmic curve: straight through the shadows, so shade keeps its colour and detail, and a soft shoulder
            // toward white.
            vec3 filmic(vec3 x) { x = max(x * 2.7, 0.0); return x * inversesqrt(1.0 + x * x); }

            """;
    /** From linear light to the screen: the filmic curve, then the game's fog; needs {@link #FRAME} and {@code sceneDepth}. */
    static final String SCREEN = TONE + """
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
                for (int i = 0; i < 4; i++) {
                    float along = (float(i) + offset) / 4.0;
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
                    // Each of the four pixels that share what they find aims at its own spot on the sun's disc, turned
                    // from one four to the next, so a shadow's edge softens the further it falls from what casts it.
                    float turn = 6.2831853 * (part * 0.25 + fract(52.9829189 * fract(dot(vec2(pixel >> 1), vec2(0.06711056, 0.00583715)))));
                    vec3 across = normalize(cross(light.xyz, vec3(0.0, 1.0, 0.01)));
                    vec3 toward = normalize(light.xyz + (across * cos(turn) + cross(light.xyz, across) * sin(turn)) * 0.035 * sqrt((part + 0.5) * 0.25));
                    float traced = blocked(origin, toward, 256.0) ? 0.0 : transmitted(origin, toward, 256.0);
                    return mix(traced, guess, smoothstep(0.75 * reach, reach, distance));
                }
            #else
                if (reach > 0.0 && guess > 0.0) return guess * walked(position, normal, distance, reach, pixel, part);
            #endif
                return guess;
            }

            // What one pixel holds before it is lit; shadow is the share of the sun it found for itself.
            struct Seen { vec4 color; vec3 normal; vec3 position; float blockLevel; float skyLevel; float shadow; bool inside; bool terrain; bool soft; };

            // Reads one pixel; part is its place among the four that share what they found of the sun.
            Seen seenAt(ivec2 pixel, float part) {
                Seen seen;
                seen.inside = all(lessThan(uvec2(pixel), size.xy));
                seen.terrain = false;
                seen.soft = false;
                seen.shadow = 0.0;
                if (!seen.inside) return seen;
                seen.color = texelFetch(sceneColor, pixel, 0);
                vec4 surface = imageLoad(surfaces, pixel);
                if (surface.a == 0.0) return seen;
                imageStore(surfaces, pixel, vec4(0.0));
                seen.terrain = true;
                seen.normal = unfold(surface.xy * 2.0 - 1.0);
                // The block light level shares its byte with the mark of leaves and plants, which the sun passes through.
                int marked = int(surface.z * 255.0 + 0.5);
                seen.soft = (marked & 1) != 0;
                seen.blockLevel = float(marked >> 1) / 127.0;
                seen.skyLevel = clamp((surface.a * 255.0 - 1.0) / 254.0, 0.0, 1.0);
                seen.position = positionOf(pixel);
                float facing = dot(seen.normal, light.xyz);
                if ((facing > 0.0 || seen.soft) && light.w > 0.0)
                    seen.shadow = shadowAt(seen.position, facing > 0.0 ? seen.normal : -seen.normal, seen.skyLevel, length(seen.position), pixel, part);
                if (seen.soft) seen.shadow = 0.4 + 0.6 * seen.shadow;
                return seen;
            }

            // The game's sky with the air's own light added: paled by haze, most at the horizon, deeper overhead, bright
            // around the sun or moon. Only where nothing was drawn over the sky, and not in fog too thick to see it.
            // Where the camera is among the clouds and how far below them: with a traced scene just before its hit
            // table, since the table's address takes the words' place.
            uvec2 cloudWords() {
            #ifdef TRACED
                return Table(uvec2(size.z - 16u, size.w - (size.z < 16u ? 1u : 0u))).e[0].xy;
            #else
                return size.zw;
            #endif
            }

            // The faint blue the air keeps at night.
            vec3 nightAir() { return vec3(0.007, 0.016, 0.028) * (1.0 - day()); }

""" + BlazeShadingClouds.SOURCE + """
            // The colour a low sun gives the air.
            const vec3 DUSK = vec3(0.7, 0.22, 0.05);

            vec3 skyOf(vec3 drawn, ivec2 pixel, float shaft) {
                if (light.w <= 0.0 || uintBitsToFloat(options.w) < 8.0 || texelFetch(sceneDepth, pixel, 0).r < 1.0) return drawn;
                vec3 toward = normalize(positionOf(pixel));
                float near = max(dot(toward, light.xyz), 0.0);
                float low = pow(1.0 - clamp(toward.y, 0.0, 1.0), 5.0) * smoothstep(-0.2, 0.0, toward.y);
                vec3 air = linear(drawn);
                // A low sun colours the air; the night's air keeps a faint blue of its own.
                // The sky keeps the dusk's colour while the sun's light on the ground already fades.
                float dusk = sunLit() ? (1.0 - smoothstep(0.05, 0.45, light.y)) * min(light.w * 8.0, 1.0) * smoothstep(0.235, 0.3, sky.w) : 0.0;
                float lit = max(light.w, 0.75 * dusk);
                // Haze pales the blue toward the horizon, and takes the low sun's colour.
                vec3 haze = mix(vec3(0.4, 0.8, 1.0) * 1.2, vec3(1.0, 0.5, 0.2) * 1.3, dusk) * dot(air, vec3(0.3, 0.59, 0.11)) * 1.12;
                // The day's blue leans to cyan, not violet.
                air *= mix(vec3(1.0), vec3(0.86, 1.0, 1.04), day() * (1.0 - dusk));
                float high = smoothstep(0.05, 0.8, toward.y);
                air = mix(air, haze, mix(0.7, 0.05, smoothstep(0.0, 0.3, toward.y))) * mix(0.85, 0.42, high) * (1.0 - 0.72 * dusk);
                // Overhead the blue deepens.
                air = max(mix(vec3(dot(air, vec3(0.3, 0.59, 0.11))), air, 1.0 + 0.25 * high), 0.0) + nightAir();
                // A low sun turns the air along the horizon orange, strongest beneath itself.
                float band = pow(1.0 - clamp(abs(toward.y), 0.0, 1.0), 2.5);
                air = mix(air, DUSK * (0.6 + 0.8 * near * near), dusk * min(band * 1.25, 1.0) * 0.95);
                // The rest of the dusk sky warms with it.
                air = mix(air, dot(air, vec3(0.3, 0.59, 0.11)) * vec3(1.3, 0.85, 0.55), dusk * 0.6 * (1.0 - 0.75 * high)) * (1.0 - 0.4 * dusk * high);
                // High above a low sun the sky is already a dark slate blue.
                air = mix(air, vec3(0.02, 0.032, 0.055), dusk * smoothstep(0.08, 0.42, toward.y) * 0.85);
                vec3 glow = mix(sunColor(), vec3(1.0, 0.38, 0.1), dusk) * lit * (0.18 * pow(near, 6.0) + 0.5 * pow(near, 96.0) + 0.12 * low * (0.3 + 0.7 * near) + 0.3 * shaft
                        + dusk * (band * 0.1 + 0.9 * pow(near, 40.0))) + vec3(1.0, 0.3, 0.06) * lit * dusk * band * (0.25 + 0.6 * near * near);
                vec4 cloud = cloudsAt(toward, pixel);
                return display(clamp((1.0 - (1.0 - air) * (1.0 - clamp(glow, 0.0, 1.0))) * (1.0 - cloud.a) + cloud.rgb, 0.0, 1.0));
            }

            // How much of the sun's light the air between the eye and these pixels catches: the way across the screen
            // to the sun is walked from a place that differs per four pixels, and each step that finds open sky adds
            // to it, so what stands before the sun cuts shafts into the light fanning out around it.
            float shaftAt(ivec2 pixel) {
            #ifdef SHAFTS
                if (light.w <= 0.0) return 0.0;
                vec4 clip = inverse(inverseViewProjection) * vec4(light.xyz, 0.0);
                if (clip.w <= 0.0) return 0.0;
                vec2 at = (vec2(pixel) + 1.0) / vec2(size.xy), toward = clip.xy / clip.w * 0.5 + 0.5 - at;
                float near = 1.0 - smoothstep(0.0, 0.85, length(toward * vec2(float(size.x) / float(size.y), 1.0)));
                if (near <= 0.0) return 0.0;
                float offset = fract(52.9829189 * fract(dot(vec2(pixel >> 1), vec2(0.06711056, 0.00583715))));
                float open = 0.0;
                for (int pace = 0; pace < 8; pace++) {
                    vec2 place = at + toward * (float(pace) + offset) * 0.125;
                    // Off the screen nothing is known to stand before the sun.
                    open += any(greaterThan(abs(place - 0.5), vec2(0.5))) ? 1.0 : step(1.0, textureLod(sceneDepth, place, 0.0).r);
                }
                return open * 0.125 * near * near;
            #else
                return 0.0;
            #endif
            }

            // How open the space before a surface is: two places about a block away on screen, turned from pixel to
            // pixel, each closing it by how squarely what lies there stands before the surface.
            float openAt(Seen seen, ivec2 pixel, float part) {
            #ifdef OCCLUSION
                float far = length(seen.position);
                if (!seen.terrain || far > 64.0) return 1.0;
                float noise = fract(52.9829189 * fract(dot(vec2(pixel >> 1), vec2(0.06711056, 0.00583715))));
                float turn = 6.2831853 * (part * 0.125 + noise), reach = clamp(0.65 * float(size.y) / far, 3.0, 80.0), closed = 0.0;
                for (int tap = 0; tap < 2; tap++) {
                    float angle = turn + 3.14159265 * float(tap);
                    ivec2 place = pixel + ivec2(vec2(cos(angle), sin(angle)) * reach * (0.4 + 0.6 * fract(noise * 7.0 + 0.5 * float(tap) + 0.25 * part)));
                    if (any(greaterThanEqual(uvec2(place), size.xy))) continue;
                    vec3 toward = positionOf(place) - seen.position;
                    float away = length(toward);
                    closed += max(dot(toward / max(away, 1e-3), seen.normal) - 0.12, 0.0) * (1.0 - smoothstep(0.7, 1.8, away));
                }
                return 1.0 - min(closed * 0.75, 0.6);
            #else
                return 1.0;
            #endif
            }

            // Lights one pixel onto the screen with its share of the sun, of the open space before it and of the light
            // the air catches; gives its linear light back, with 1 beside it where terrain is.
            vec4 lightOf(Seen seen, float shadow, float open, float shaft, ivec2 pixel) {
                if (!seen.inside) return vec4(0.0);
                if (!seen.terrain) {
                    imageStore(lit, pixel, vec4(skyOf(seen.color.rgb, pixel, shaft), seen.color.a));
                    return vec4(0.0);
                }
                vec3 normal = seen.normal;
                vec3 shaded = linear(seen.color.rgb) * daylight(normal, vec2(seen.blockLevel, seen.skyLevel), shadow, seen.soft) * open;
                // The eye loses colour in the dark, away from fire.
                float dim = 0.55 * (1.0 - day()) * (1.0 - brightness(seen.blockLevel));
                shaded = mix(shaded, vec3(0.75, 0.95, 1.2) * dot(shaded, vec3(0.3, 0.59, 0.11)), dim);
                // Daylight keeps colours rich.
                shaded = max(mix(vec3(dot(shaded, vec3(0.3, 0.59, 0.11))), shaded, 1.0 + 0.06 * day()), 0.0);
                vec3 shown = shaded;
                float exposure = 1.0;
            #ifdef LINEAR
                // An empty glow or measure is one switched off, or the first frame's.
                vec4 around = textureLod(glow, (vec2(pixel) + 0.5) / vec2(size.xy), 0.0);
                if (around.a > 0.0) shown = mix(shaded, around.rgb / around.a, 0.04);
                float average = texelFetch(measured, ivec2(0), 0).r;
                if (average > 0.0) exposure = clamp(sqrt(0.2 / average), 0.5, 1.0 + day() * smoothstep(0.05, 0.45, light.y));
            #endif
                // The further a surface, the more lit air lies before it.
                shown += sunColor() * light.w * 0.35 * shaft * smoothstep(4.0, 96.0, length(seen.position));
                // Far terrain under the open sky sinks into the air's own colour, the one the sky has at the horizon.
                float dusk = lowSun();
                vec3 air = linear(sky.rgb);
                air = mix(air, mix(vec3(0.42, 0.72, 1.0) * 0.95, sunColor() * 1.15, dusk) * dot(air, vec3(0.3, 0.59, 0.11)) * 1.3, 0.75) + nightAir();
                air = mix(air, DUSK * 0.38, dusk * 0.8);
                float sunk = min(1.0 - exp(-length(seen.position) / 280.0), 0.75) * smoothstep(0.3, 0.9, seen.skyLevel) * (1.0 - 0.85 * dusk);
                imageStore(lit, pixel, vec4(mix(screened(shown, seen.position, exposure), display(air), sunk), seen.color.a));
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
                float opens[4];
                for (int i = 0; i < 4; i++) seen[i] = seenAt(cell * 2 + ivec2(i & 1, i >> 1), float(i));
                vec4 sum = vec4(0.0);
                float shaft = shaftAt(cell * 2);
                // Clouds shade broadly, so the four pixels share what the first of them on terrain finds.
                float cloudy = 1.0;
                for (int i = 0; i < 4; i++) if (seen[i].terrain) {
                    cloudy = cloudShadeAt(seen[i].position);
                    break;
                }
                for (int i = 0; i < 4; i++) opens[i] = openAt(seen[i], cell * 2 + ivec2(i & 1, i >> 1), float(i));
                for (int i = 0; i < 4; i++) {
                    float shadow = 0.0, open = 0.0, weight = 0.0, distance = length(seen[i].position);
                    if (seen[i].terrain) for (int j = 0; j < 4; j++) {
                        if (!seen[j].terrain || dot(seen[i].normal, seen[j].normal) < 0.9) continue;
                        if (abs(length(seen[j].position) - distance) > 0.1 + 0.02 * distance) continue;
                        shadow += seen[j].shadow;
                        open += opens[j];
                        weight += 1.0;
                    }
                    sum += lightOf(seen[i], (weight > 0.0 ? shadow / weight : 0.0) * cloudy, weight > 0.0 ? open / weight : 1.0, shaft, cell * 2 + ivec2(i & 1, i >> 1));
                }
            #ifdef LINEAR
                if (all(lessThan(cell, imageSize(spread)))) imageStore(spread, cell, sum * 0.25);
            #endif
            }
            """;

    /**
     * The plain variant's source, or with {@code traced} the one finding sun shadows with ray queries; with
     * {@code linear} it also leaves linear light at half size and shows the glow and exposure made of the frame before;
     * with {@code shafts} the air catches the sun's light; with {@code occlusion} corners and creases darken.
     */
    static String source(boolean traced, boolean linear, boolean shafts, boolean occlusion) {
        String head = HEAD + (linear ? "#define LINEAR\n" : "") + (shafts ? "#define SHAFTS\n" : "") + (occlusion ? "#define OCCLUSION\n" : "");
        if (!traced) return head + BINDINGS + BlazeRayShader.LIGHTS + SCREEN + BODY;
        return head + TRACED_HEAD + BINDINGS + BlazeRayShader.BUFFERS + BlazeRayShader.SCENE + BlazeRayShader.LIGHTS + SCREEN + BODY;
    }

    private BlazeShadingShader() {}
}
