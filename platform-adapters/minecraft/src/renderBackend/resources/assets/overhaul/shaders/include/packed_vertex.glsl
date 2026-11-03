#ifndef OVERHAUL_PACKED_VERTEX_GLSL
#define OVERHAUL_PACKED_VERTEX_GLSL

vec4 overhaulUnpackArgb(uint color) {
    return vec4(float((color >> 16u) & 255u), float((color >> 8u) & 255u),
                float(color & 255u), float(color >> 24u)) / 255.0;
}

ivec2 overhaulUnpackSignedShorts(uint value) {
    ivec2 pair = ivec2(int(value & 65535u), int(value >> 16u));
    return (pair ^ ivec2(32768)) - ivec2(32768);
}

vec3 overhaulUnpackNormal(uint normal) {
    ivec3 bytes = ivec3(int(normal & 255u), int((normal >> 8u) & 255u), int((normal >> 16u) & 255u));
    bytes = (bytes ^ ivec3(128)) - ivec3(128);
    return max(vec3(-1.0), vec3(bytes) / 127.0);
}

#endif
