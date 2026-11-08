package dev.overhaul.minecraft.blaze;

/** Distant Horizons' own GLSL with its uniforms gathered into blocks; the logic stays line for line so LODs look the same on Vulkan. */
final class BlazeDhShaders {
    private static final String TERRAIN_BLOCK = """
            layout(set=0,binding=0,std140) uniform Terrain {
                mat4 uCombinedMatrix;
                float uMircoOffset, uEarthRadius, uWorldYOffset, uClipDistance;
                int uNoiseSteps; float uNoiseIntensity; int uNoiseDropoff; int uFlags;
            };
            """;

    static final String BOX_VERTEX = """
            #version 450
            layout(set=0,binding=0,std140) uniform Boxes { mat4 uProjectionMvm; };
            layout(push_constant) uniform Group {
                ivec3 uOffsetChunk; int uSkyLight;
                vec3 uOffsetSubChunk; int uBlockLight;
                float uNorthShading, uSouthShading, uEastShading, uWestShading, uTopShading, uBottomShading;
            };
            layout(set=0,binding=1) uniform sampler2D uLightMap;
            layout(location=0) in vec3 vPosition;
            layout(location=1) in vec4 aColor;
            layout(location=2) in vec3 aScale;
            layout(location=3) in ivec3 aTranslateChunk;
            layout(location=4) in vec3 aTranslateSubChunk;
            layout(location=0) out vec4 fColor;
            void main() {
                vec3 trans = vec3(aTranslateChunk + uOffsetChunk) * 16.0 + aTranslateSubChunk + uOffsetSubChunk;
                gl_Position = uProjectionMvm * vec4(vPosition * aScale + trans, 1.0);
                gl_Position.z = 0.5 * (gl_Position.w + gl_Position.z);
                float blockLight = (float(uBlockLight) + 0.5) / 16.0;
                float skyLight = (float(uSkyLight) + 0.5) / 16.0;
                fColor = vec4(texture(uLightMap, vec2(blockLight, skyLight)).xyz, 1.0) * aColor;
                if (gl_VertexIndex < 4) fColor.rgb *= uNorthShading;
                else if (gl_VertexIndex < 8) fColor.rgb *= uSouthShading;
                else if (gl_VertexIndex < 12) fColor.rgb *= uWestShading;
                else if (gl_VertexIndex < 16) fColor.rgb *= uEastShading;
                else if (gl_VertexIndex < 20) fColor.rgb *= uBottomShading;
                else fColor.rgb *= uTopShading;
            }
            """;

    static final String BOX_FRAGMENT = """
            #version 450
            layout(location=0) in vec4 fColor;
            layout(location=0) out vec4 fragColor;
            void main() { fragColor = fColor; }
            """;

    /** DH's debug box outline: its unit cube's twelve edges come from the vertex index instead of a buffer. */
    static final String WIREFRAME_VERTEX = """
            #version 450
            layout(set=0,binding=0,std140) uniform Wireframe { mat4 uProjectionMvm; };
            layout(push_constant) uniform Box { vec4 uOffset; vec4 uSize; vec4 uColor; };
            layout(location=0) out vec4 fColor;
            // Each corner's bits are its x, y and z.
            const int CORNERS[24] = int[](0,1, 2,3, 4,5, 6,7, 0,2, 1,3, 4,6, 5,7, 0,4, 1,5, 2,6, 3,7);
            void main() {
                int corner = CORNERS[gl_VertexIndex];
                vec3 unit = vec3(corner & 1, (corner >> 1) & 1, (corner >> 2) & 1);
                gl_Position = uProjectionMvm * vec4(uOffset.xyz + unit * uSize.xyz, 1.0);
                gl_Position.z = 0.5 * (gl_Position.w + gl_Position.z);
                fColor = uColor;
            }
            """;

    static final String TERRAIN_VERTEX = "#version 450\n" + TERRAIN_BLOCK + """
            layout(location=0) in uvec4 vPosition;
            layout(location=1) in vec4 color;
            layout(location=2) in uvec4 irisData;
            layout(push_constant) uniform Offset { vec3 uModelOffset; };
            layout(set=0,binding=1) uniform sampler2D uLightMap;
            layout(location=0) out vec4 vPos;
            layout(location=1) out vec4 vertexColor;
            layout(location=2) out vec3 vertexWorldPos;
            layout(location=3) out vec3 vBlockPos;
            layout(location=4) flat out uint vNormalIndex;
            layout(location=5) flat out uint vTextureTileId;
            void main() {
                vPos = vec4(vPosition);
                vBlockPos = vec3(vPosition.xyz);
                vNormalIndex = irisData.y;
                vTextureTileId = irisData.z | (irisData.w << 8u);
                vertexWorldPos = vec3(vPosition.xyz) + uModelOffset;
                float vertexYPos = float(vPosition.y) + uWorldYOffset;
                uint meta = vPosition.a;
                uint mirco = (meta & 0xFF00u) >> 8u;
                float mx = (mirco & 1u) != 0u ? uMircoOffset : 0.0;
                mx = (mirco & 2u) != 0u ? -mx : mx;
                float mz = (mirco & 16u) != 0u ? uMircoOffset : 0.0;
                mz = (mirco & 32u) != 0u ? -mz : mz;
                vertexWorldPos.x += mx;
                vertexWorldPos.z += mz;
                if (uEarthRadius < -1.0 || uEarthRadius > 1.0) {
                    float localRadius = uEarthRadius + vertexYPos;
                    float phi = length(vertexWorldPos.xz) / localRadius;
                    vertexWorldPos.y += (cos(phi) - 1.0) * localRadius;
                    vertexWorldPos.xz = vertexWorldPos.xz * sin(phi) / phi;
                }
                uint lights = meta & 0xFFu;
                float skyLight = (float(lights / 16u) + 0.5) / 16.0;
                float blockLight = (mod(float(lights), 16.0) + 0.5) / 16.0;
                vertexColor = vec4(texture(uLightMap, vec2(skyLight, blockLight)).xyz, 1.0);
                if ((uFlags & 4) == 0) vertexColor *= color;
                gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);
                gl_Position.z = 0.5 * (gl_Position.w + gl_Position.z);
            }
            """;

    static final String TERRAIN_FRAGMENT = "#version 450\n" + TERRAIN_BLOCK + """
            layout(set=0,binding=2) uniform sampler2D uBlockAtlas;
            layout(location=0) in vec4 vPos;
            layout(location=1) in vec4 vertexColor;
            layout(location=2) in vec3 vertexWorldPos;
            layout(location=3) in vec3 vBlockPos;
            layout(location=4) flat in uint vNormalIndex;
            layout(location=5) flat in uint vTextureTileId;
            layout(location=0) out vec4 fragColor;
            float rand(float co) { return fract(sin(co * 91.3458) * 47453.5453); }
            float rand(vec2 co) { return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453); }
            float rand(vec3 co) { return rand(co.xy + rand(co.z)); }
            vec3 quantize(vec3 val, int stepSize) { return floor(val * stepSize) / stepSize; }
            void applyNoise(inout vec4 color, float viewDist) {
                vec3 vertexNormal = normalize(cross(dFdy(vPos.xyz), dFdx(vPos.xyz)));
                vec3 fixedVPos = vPos.xyz + vertexNormal * 0.001;
                float lum = (color.r + color.g + color.b) / 3.0;
                float amplification = (1.0 - pow(lum * 2.0 - 1.0, 2.0)) * uNoiseIntensity * color.a;
                float randomValue = rand(quantize(fixedVPos, uNoiseSteps)) * 2.0 * amplification - amplification;
                vec3 newCol = clamp(color.rgb + (1.0 - color.rgb) * randomValue, 0.0, 1.0);
                if (uNoiseDropoff != 0) newCol = mix(newCol, color.rgb, min(viewDist / uNoiseDropoff, 1.0));
                color.rgb = newCol;
            }
            float bayerMatrix4x4(vec2 st) {
                const float bayer[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
                return bayer[int(mod(st.y, 4.0)) * 4 + int(mod(st.x, 4.0))] / 16.0;
            }
            vec2 blockFaceUv() {
                vec3 pos = fract(vBlockPos);
                switch (vNormalIndex) {
                    case 0u: return vec2(pos.x, 1.0 - pos.z);
                    case 1u: return vec2(pos.x, pos.z);
                    case 2u: return vec2(1.0 - pos.x, 1.0 - pos.y);
                    case 3u: return vec2(pos.x, 1.0 - pos.y);
                    case 4u: return vec2(pos.z, 1.0 - pos.y);
                    default: return vec2(1.0 - pos.z, 1.0 - pos.y);
                }
            }
            void main() {
                fragColor = vertexColor;
                if (vTextureTileId != 0u) {
                    vec2 atlasSize = vec2(textureSize(uBlockAtlas, 0));
                    vec2 tileOrigin = vec2(float(vTextureTileId % 256u), float(vTextureTileId / 256u)) * 16.0;
                    vec4 tile = texture(uBlockAtlas, (tileOrigin + blockFaceUv() * 16.0) / atlasSize);
                    fragColor.rgb = mix(fragColor.rgb, clamp(fragColor.rgb * (tile.rgb * 2.0), 0.0, 1.0), tile.a);
                }
                float viewDist = length(vertexWorldPos);
                if ((uFlags & 2) != 0) {
                    float worldNoise = bayerMatrix4x4(gl_FragCoord.xy) + 0.001;
                    if (smoothstep(uClipDistance, uClipDistance * 1.5, viewDist) <= worldNoise) discard;
                } else if (viewDist < uClipDistance && uClipDistance > 0.0) {
                    discard;
                }
                if ((uFlags & 1) != 0 && vTextureTileId == 0u) applyNoise(fragColor, viewDist);
            }
            """;

    static final String COMPOSITE_VERTEX = """
            #version 450
            layout(location=0) out vec2 texCoord;
            void main() {
                texCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** DH's fog pass, shared by the two passes that put LOD colour on the frame: its render target was only ever read by them. */
    private static final String COMPOSITE_HEAD = """
            #version 450
            layout(set=0,binding=0,std140) uniform Composite {
                mat4 uInvMvmProj;
                vec4 uFogColor;
                float uFogScale, uFogVerticalScale; int uFogFalloffType; float uFarFogStart;
                float uFarFogLength, uFarFogMin, uFarFogRange, uFarFogDensity;
                float uHeightFogStart, uHeightFogLength; int uHeightFogFalloffType, uHeightFogMixingMode;
                float uHeightFogBaseHeight, uCameraBlockYPos, uFadeStart, uFadeEnd;
                int uFlags;
                float uMaxLevelHeight;
                mat4 uMcInvMvmProj;
            };
            layout(set=0,binding=1) uniform sampler2D uDhColor;
            layout(set=0,binding=2) uniform sampler2D uDhDepth;
            layout(location=0) in vec2 texCoord;
            layout(location=0) out vec4 fragColor;
            const int FOG = 1, HEIGHT = 2, CAMERA = 4, UP = 8, DOWN = 16, SPHERICAL = 32;
            float falloff(int type, float x, float start, float len) {
                if (type == 0) return uFarFogMin + uFarFogRange * clamp((x - start) / len, 0.0, 1.0);
                x = max((x - start) / len, 0.0) * uFarFogDensity;
                return uFarFogMin + uFarFogRange - uFarFogRange / exp(type == 1 ? x : x * x);
            }
            float heightDepth(float y) {
                if ((uFlags & CAMERA) == 0) y -= uHeightFogBaseHeight - uCameraBlockYPos;
                bool up = (uFlags & UP) != 0, down = (uFlags & DOWN) != 0;
                if (up && down) return abs(y) * uFogVerticalScale;
                if (down) return -y * uFogVerticalScale;
                return up ? y * uFogVerticalScale : 0.0;
            }
            float mixFog(float far, float height) {
                switch (uHeightFogMixingMode) {
                    case 2: return max(far, height);
                    case 3: return far + height;
                    case 4: return far * height;
                    case 5: return 1.0 - (1.0 - far) * (1.0 - height);
                    case 6: return far + max(far, height);
                    case 7: return far + far * height;
                    case 8: return far + 1.0 - (1.0 - far) * (1.0 - height);
                    case 9: return far * 0.5 + height * 0.5;
                    default: return far;
                }
            }
            vec3 unproject(mat4 inverse, float depth) {
                vec4 eye = inverse * (vec4(texCoord, depth, 1.0) * vec4(2.0, 2.0, 2.0, 1.0) - vec4(1.0, 1.0, 1.0, 0.0));
                return eye.xyz / eye.w;
            }
            vec3 fogged(vec3 color, vec3 position) {
                if ((uFlags & FOG) == 0) return color;
                float distance = ((uFlags & SPHERICAL) != 0 ? length(position) : length(position.xz)) * uFogScale;
                float far = falloff(uFogFalloffType, distance, uFarFogStart, uFarFogLength);
                float height = (uFlags & HEIGHT) != 0 ? falloff(uHeightFogFalloffType, heightDepth(position.y), uHeightFogStart, uHeightFogLength) : 0.0;
                return mix(color, uFogColor.rgb, clamp(mixFog(far, height), 0.0, 1.0));
            }
            """;

    /** DH's far fade folded into the pass that puts LODs behind the frame's terrain. */
    static final String COMPOSITE_FRAGMENT = COMPOSITE_HEAD + """
            void main() {
                vec4 color = texture(uDhColor, texCoord);
                if (color.a == 0.0) discard;
                vec3 position = unproject(uInvMvmProj, texture(uDhDepth, texCoord).r);
                fragColor = vec4(fogged(color.rgb, position), color.a * (1.0 - smoothstep(uFadeStart, uFadeEnd, length(position))));
            }
            """;

    /**
     * DH's vanilla fade: vanilla pixels past the fade start give way to the LOD behind them. The blend does the mix
     * DH's shader did by hand, so the frame needs no copy to read itself.
     */
    static final String VANILLA_FADE_FRAGMENT = COMPOSITE_HEAD + """
            layout(set=0,binding=3) uniform sampler2D uMcDepth;
            void main() {
                vec4 color = texture(uDhColor, texCoord);
                if (color.a == 0.0) discard;
                float mcDepth = texture(uMcDepth, texCoord).r;
                if (mcDepth >= 1.0) discard;
                vec3 position = unproject(uInvMvmProj, texture(uDhDepth, texCoord).r);
                if (position.y > uMaxLevelHeight) discard;
                fragColor = vec4(fogged(color.rgb, position), smoothstep(uFadeStart, uFadeEnd, length(unproject(uMcInvMvmProj, mcDepth))));
            }
            """;

    /** DH's ambient occlusion, with its fixed sample settings and GL's depth range folded in. */
    static final String SSAO_FRAGMENT = """
            #version 450
            layout(set=0,binding=0,std140) uniform Occlusion { mat4 uProj; mat4 uInvProj; };
            layout(set=0,binding=1) uniform sampler2D uDhDepthTexture;
            layout(location=0) in vec2 texCoord;
            layout(location=0) out vec4 fragColor;
            const int SAMPLE_COUNT = 6;
            const float RADIUS = 4.0, STRENGTH = 0.2, MIN_LIGHT = 0.25, BIAS = 0.02, FADE_DISTANCE = 1600.0;
            const float EPSILON = 1.e-6, GOLDEN_ANGLE = 2.39996323, TAU = 6.2831853076;
            const vec3 MAGIC = vec3(0.06711056, 0.00583715, 52.9829189);
            vec3 unproject(vec4 position) { return position.xyz / position.w; }
            float noise(vec2 pixel) { return fract(MAGIC.z * fract(dot(pixel, MAGIC.xy))); }
            float spiralOcclusion(vec3 viewPos, vec3 viewNormal) {
                float phase = noise(gl_FragCoord.xy) * TAU;
                float stride = RADIUS / SAMPLE_COUNT, radius = stride, ao = 0.0;
                int counted = 0;
                for (int i = 0; i < SAMPLE_COUNT; i++) {
                    vec2 offset = vec2(sin(phase), cos(phase)) * radius;
                    radius += stride;
                    phase += GOLDEN_ANGLE;
                    vec3 clip = clamp(unproject(uProj * vec4(viewPos + vec3(offset, -0.1), 1.0)) * 0.5 + 0.5, 0.0, 1.0);
                    float depth = textureLod(uDhDepthTexture, clip.xy, 0.0).r;
                    if (depth >= 1.0 - EPSILON) continue;
                    clip.z = depth;
                    vec3 diff = unproject(uInvProj * vec4(clip * 2.0 - 1.0, 1.0)) - viewPos;
                    float gap = length(diff);
                    ao += max(dot(viewNormal, diff / gap) - BIAS, 0.0) * (1.0 - clamp(gap / RADIUS, 0.0, 1.0));
                    counted++;
                }
                ao /= max(counted, 1);
                return smoothstep(0.0, STRENGTH, ao) * (1.0 - MIN_LIGHT);
            }
            void main() {
                float depth = textureLod(uDhDepthTexture, texCoord, 0.0).r;
                vec3 viewPos = unproject(uInvProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0));
                // Derivatives outside the branch, where every lane of the quad still runs.
                vec3 viewNormal = normalize(cross(dFdxFine(viewPos), dFdyFine(viewPos)));
                float occlusion = 0.0, reach = length(viewPos);
                if (depth < 1.0 && reach < FADE_DISTANCE) occlusion = spiralOcclusion(viewPos, viewNormal) * (FADE_DISTANCE - reach) / FADE_DISTANCE;
                fragColor = vec4(vec3(1.0 - occlusion), 1.0);
            }
            """;

    /** DH's depth-aware blur of the occlusion; the blend multiplies the LOD colour by its alpha. */
    static final String SSAO_APPLY_FRAGMENT = """
            #version 450
            layout(set=0,binding=0,std140) uniform Apply { vec2 uViewSize; float uNearClipPlane, uFarClipPlane; };
            layout(set=0,binding=1) uniform sampler2D uSourceColorTexture;
            layout(set=0,binding=2) uniform sampler2D uSourceDepthTexture;
            layout(location=0) in vec2 texCoord;
            layout(location=0) out vec4 fragColor;
            const int BLUR_RADIUS = 2;
            const float SIGMA = 1.6;
            float linearizeDepth(float depth) { return (uNearClipPlane * uFarClipPlane) / (depth * (uNearClipPlane - uFarClipPlane) + uFarClipPlane); }
            float gaussian(float x) { return exp(-(x * x) / (2.0 * SIGMA * SIGMA)); }
            void main() {
                fragColor = vec4(1.0);
                float depth = textureLod(uSourceDepthTexture, texCoord, 0.0).r;
                if (depth >= 1.0) return;
                float linearDepth = linearizeDepth(depth), accum = 0.0, total = 0.0;
                vec2 pixelSize = 1.0 / uViewSize;
                for (int y = -BLUR_RADIUS; y <= BLUR_RADIUS; y++) {
                    for (int x = -BLUR_RADIUS; x <= BLUR_RADIUS; x++) {
                        vec2 coord = texCoord + vec2(x, y) * pixelSize;
                        float sampleDepth = linearizeDepth(textureLod(uSourceDepthTexture, coord, 0.0).r);
                        float weight = gaussian(float(x)) * gaussian(float(y)) * gaussian(abs(sampleDepth - linearDepth));
                        accum += weight * textureLod(uSourceColorTexture, coord, 0.0).r;
                        total += weight;
                    }
                }
                fragColor.a = total <= 1.e-4 ? 1.0 : accum / total;
            }
            """;

    private BlazeDhShaders() {}
}
