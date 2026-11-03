#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec2 Corner;
layout(location = 1) in vec3 InstanceCenter;
layout(location = 2) in vec4 InstanceRotation;
layout(location = 3) in float InstanceSize;
layout(location = 4) in vec4 InstanceUv;
layout(location = 5) in uint InstanceColor;
layout(location = 6) in uint InstanceLight;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;

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
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
    texCoord0 = vec2(Corner.x > 0.0 ? InstanceUv.y : InstanceUv.x,
                    Corner.y > 0.0 ? InstanceUv.z : InstanceUv.w);
    vec4 color = vec4(float((InstanceColor >> 16u) & 255u), float((InstanceColor >> 8u) & 255u),
                      float(InstanceColor & 255u), float(InstanceColor >> 24u)) / 255.0;
    ivec2 light = ivec2(int(InstanceLight & 65535u), int(InstanceLight >> 16u));
    light = (light ^ ivec2(32768)) - ivec2(32768);
    vertexColor = color * sample_lightmap(Sampler2, light);
}
