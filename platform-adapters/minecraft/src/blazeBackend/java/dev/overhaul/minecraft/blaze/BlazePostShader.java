package dev.overhaul.minecraft.blaze;

/**
 * The compute shader behind the world's own anti-aliasing and sharpening. FXAA follows the shape of FXAA 3.11's quality
 * preset: it finds a luma edge, walks along it in growing steps to both ends and blends the pixel across the edge by
 * how far it sits from the nearer end, plus a sub-pixel blend for lone bright pixels. Sharpening is contrast adaptive:
 * each pixel sharpens against its four neighbours by as much as its local contrast leaves headroom for, so flat areas
 * sharpen fully and edges do not ring.
 */
final class BlazePostShader {
    static final int FXAA = 1, SHARPEN = 2;
    static final int PUSH_BYTES = 16;

    static final String SOURCE = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;
            layout(binding = 0) uniform sampler2D source;
            layout(binding = 1, rgba8) writeonly uniform image2D result;
            // size: pixels; mode: FXAA or SHARPEN; strength: sharpening from 0 to 1.
            layout(push_constant) uniform Pass { ivec2 size; uint mode; float strength; };

            const float EDGE_MIN = 0.0312, EDGE_MAX = 0.125, SUBPIXEL = 0.75;
            const int STEPS = 12;
            const float STEP[STEPS] = float[](1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 2.0, 2.0, 4.0, 8.0);

            vec2 texel;

            float luma(vec3 color) { return dot(color, vec3(0.299, 0.587, 0.114)); }
            vec3 at(vec2 uv) { return textureLod(source, uv, 0.0).rgb; }
            float lumaAt(vec2 uv, vec2 offset) { return luma(at(uv + offset * texel)); }

            vec3 fxaa(vec2 uv) {
                vec3 color = at(uv);
                float c = luma(color);
                float n = lumaAt(uv, vec2(0, -1)), s = lumaAt(uv, vec2(0, 1)), e = lumaAt(uv, vec2(1, 0)), w = lumaAt(uv, vec2(-1, 0));
                float low = min(c, min(min(n, s), min(e, w))), high = max(c, max(max(n, s), max(e, w))), range = high - low;
                if (range < max(EDGE_MIN, high * EDGE_MAX)) return color;
                float nw = lumaAt(uv, vec2(-1, -1)), ne = lumaAt(uv, vec2(1, -1)), sw = lumaAt(uv, vec2(-1, 1)), se = lumaAt(uv, vec2(1, 1));
                float vertical = n + s, sideways = e + w, west = nw + sw, east = ne + se, north = nw + ne, south = sw + se;
                float acrossRows = abs(-2.0 * w + west) + abs(-2.0 * c + vertical) * 2.0 + abs(-2.0 * e + east);
                float acrossColumns = abs(-2.0 * n + north) + abs(-2.0 * c + sideways) * 2.0 + abs(-2.0 * s + south);
                bool horizontal = acrossRows >= acrossColumns;
                float first = horizontal ? n : w, second = horizontal ? s : e;
                float firstGradient = first - c, secondGradient = second - c;
                bool firstSteeper = abs(firstGradient) >= abs(secondGradient);
                float threshold = 0.25 * max(abs(firstGradient), abs(secondGradient));
                float stride = horizontal ? texel.y : texel.x;
                float edgeLuma;
                if (firstSteeper) { stride = -stride; edgeLuma = 0.5 * (first + c); } else edgeLuma = 0.5 * (second + c);
                vec2 middle = uv + (horizontal ? vec2(0, stride * 0.5) : vec2(stride * 0.5, 0));
                vec2 along = horizontal ? vec2(texel.x, 0) : vec2(0, texel.y);
                vec2 back = middle - along, ahead = middle + along;
                float backLuma = luma(at(back)) - edgeLuma, aheadLuma = luma(at(ahead)) - edgeLuma;
                bool backDone = abs(backLuma) >= threshold, aheadDone = abs(aheadLuma) >= threshold;
                for (int i = 0; i < STEPS && !(backDone && aheadDone); i++) {
                    if (!backDone) { back -= along * STEP[i]; backLuma = luma(at(back)) - edgeLuma; backDone = abs(backLuma) >= threshold; }
                    if (!aheadDone) { ahead += along * STEP[i]; aheadLuma = luma(at(ahead)) - edgeLuma; aheadDone = abs(aheadLuma) >= threshold; }
                }
                float toBack = horizontal ? uv.x - back.x : uv.y - back.y, toAhead = horizontal ? ahead.x - uv.x : ahead.y - uv.y;
                bool backCloser = toBack < toAhead;
                float offset = 0.5 - min(toBack, toAhead) / (toBack + toAhead);
                bool darker = c < edgeLuma;
                if (((backCloser ? backLuma : aheadLuma) < 0.0) == darker) offset = 0.0;
                float average = (2.0 * (vertical + sideways) + north + south) / 12.0;
                float lone = clamp(abs(average - c) / range, 0.0, 1.0);
                lone = (-2.0 * lone + 3.0) * lone * lone;
                offset = max(offset, lone * lone * SUBPIXEL);
                return at(uv + (horizontal ? vec2(0, offset * stride) : vec2(offset * stride, 0)));
            }

            vec3 fetch(ivec2 pixel) { return texelFetch(source, clamp(pixel, ivec2(0), size - 1), 0).rgb; }

            vec3 sharpen(ivec2 pixel) {
                vec3 a = fetch(pixel + ivec2(-1, -1)), b = fetch(pixel + ivec2(0, -1)), c = fetch(pixel + ivec2(1, -1));
                vec3 d = fetch(pixel + ivec2(-1, 0)), e = fetch(pixel), f = fetch(pixel + ivec2(1, 0));
                vec3 g = fetch(pixel + ivec2(-1, 1)), h = fetch(pixel + ivec2(0, 1)), i = fetch(pixel + ivec2(1, 1));
                vec3 low = min(min(min(d, e), min(f, b)), h);
                low += min(low, min(min(a, c), min(g, i)));
                vec3 high = max(max(max(d, e), max(f, b)), h);
                high += max(high, max(max(a, c), max(g, i)));
                vec3 amount = sqrt(clamp(min(low, 2.0 - high) / max(high, vec3(1e-4)), 0.0, 1.0));
                vec3 weight = amount * (-1.0 / mix(8.0, 5.0, strength));
                return clamp((b * weight + d * weight + f * weight + h * weight + e) / (1.0 + 4.0 * weight), 0.0, 1.0);
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(pixel, size))) return;
                texel = 1.0 / vec2(size);
                vec3 color = mode == 1u ? fxaa((vec2(pixel) + 0.5) * texel) : sharpen(pixel);
                imageStore(result, pixel, vec4(color, 1.0));
            }
            """;

    private BlazePostShader() {}
}
