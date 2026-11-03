#ifndef OVERHAUL_INSTANCED_PARTICLE_VERTEX_GLSL
#define OVERHAUL_INSTANCED_PARTICLE_VERTEX_GLSL

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec4 vertexColor;

void overhaulParticleVertex(vec3 position, vec2 uv, uint packedColor, uint packedLight) {
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
    texCoord0 = uv;
    vec4 color = vec4(float((packedColor >> 16u) & 255u), float((packedColor >> 8u) & 255u),
                      float(packedColor & 255u), float(packedColor >> 24u)) / 255.0;
    ivec2 light = ivec2(int(packedLight & 65535u), int(packedLight >> 16u));
    light = (light ^ ivec2(32768)) - ivec2(32768);
    vertexColor = color * sample_lightmap(Sampler2, light);
}

#endif
