#version 330
#extension GL_ARB_separate_shader_objects : require

#include <overhaul:instanced_particle_vertex.glsl>

layout(location = 0) in vec2 Corner;
layout(location = 1) in vec4 InstanceHorizontal;
layout(location = 2) in vec2 InstanceVertical;
layout(location = 3) in vec4 InstanceUv;
layout(location = 4) in uint InstanceColor;
layout(location = 5) in uint InstanceLight;

void main() {
    bool right = Corner.y > 0.0;
    bool top = Corner.x > 0.0;
    vec3 position = vec3(right ? InstanceHorizontal.z : InstanceHorizontal.x,
                         top ? InstanceVertical.x : InstanceVertical.y,
                         right ? InstanceHorizontal.w : InstanceHorizontal.y);
    // Vanilla deliberately maps its bottom-Y UV to the top world vertices.
    vec2 uv = vec2(right ? InstanceUv.y : InstanceUv.x, top ? InstanceUv.z : InstanceUv.w);
    overhaulParticleVertex(position, uv, InstanceColor, InstanceLight);
}
