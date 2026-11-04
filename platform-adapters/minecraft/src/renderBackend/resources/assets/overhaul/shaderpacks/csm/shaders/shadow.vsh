#version 120
varying vec2 casterUv;
void main() {
    casterUv = gl_MultiTexCoord0.xy;
    gl_Position = ftransform();
}
