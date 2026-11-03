#version 120
#include "/lib/options.glsl"
uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec2 uv;
varying vec2 lightUv;
varying vec4 tint;
varying vec3 normal;
#if LEGACY_LIGHTING && __VERSION__ == 120
/* DRAWBUFFERS:02 */
#else
/* DRAWBUFFERS:01 */
uniform unsupported inactive;
#include "this-file-must-not-be-opened.glsl"
#endif
void main() {
    vec4 color = texture2D(texture,uv) * tint;
    color.rgb *= texture2D(lightmap,lightUv).rgb;
    gl_FragData[0] = color;
    gl_FragData[1] = vec4(normalize(normal)*0.5+0.5,1.0);
}
