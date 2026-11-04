#version 120
uniform sampler2D texture;
varying vec2 casterUv;
void main() {
    gl_FragColor = texture2D(texture, casterUv);
}
