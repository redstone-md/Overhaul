#version 330
#extension GL_ARB_separate_shader_objects : require

#include <overhaul:instanced_particle_vertex.glsl>

layout(location = 0) in vec2 Corner;
layout(location = 1) in vec3 InstanceCenter;
layout(location = 2) in vec4 InstanceRotation;
layout(location = 3) in float InstanceSize;
layout(location = 4) in vec4 InstanceUv;
layout(location = 5) in uint InstanceColor;
layout(location = 6) in uint InstanceLight;

vec3 rotateCorner(vec2 corner, vec4 q) {
    float xx = q.x * q.x;
    float yy = q.y * q.y;
    float zz = q.z * q.z;
    float ww = q.w * q.w;
    float xy = q.x * q.y;
    float xz = q.x * q.z;
    float yz = q.y * q.z;
    float xw = q.x * q.w;
    float zw = q.z * q.w;
    float yw = q.y * q.w;
    float inverseLengthSquared = 1.0 / (((xx + yy) + zz) + ww);
    // Quaternionf.transform includes this division for non-unit quaternions.
    return vec3(
        (xx - yy - zz + ww) * inverseLengthSquared * corner.x
            + (2.0 * (xy - zw)) * inverseLengthSquared * corner.y,
        (2.0 * (xy + zw)) * inverseLengthSquared * corner.x
            + (yy - xx - zz + ww) * inverseLengthSquared * corner.y,
        (2.0 * (xz - yw)) * inverseLengthSquared * corner.x
            + (2.0 * (yz + xw)) * inverseLengthSquared * corner.y
    );
}

void main() {
    vec3 position = rotateCorner(Corner, InstanceRotation) * InstanceSize + InstanceCenter;
    vec2 uv = vec2(Corner.x > 0.0 ? InstanceUv.y : InstanceUv.x,
                   Corner.y > 0.0 ? InstanceUv.z : InstanceUv.w);
    overhaulParticleVertex(position, uv, InstanceColor, InstanceLight);
}
