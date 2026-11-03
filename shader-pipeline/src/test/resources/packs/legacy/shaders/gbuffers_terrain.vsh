#version 120
#include "/lib/options.glsl"
varying vec2 uv;
varying vec2 lightUv;
varying vec4 tint;
varying vec3 normal;
void main() {
    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lightUv = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    tint = gl_Color;
    normal = gl_NormalMatrix * gl_Normal;
    gl_Position = ftransform();
}
