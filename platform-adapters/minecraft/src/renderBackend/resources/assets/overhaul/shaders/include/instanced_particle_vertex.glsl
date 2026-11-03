#ifndef OVERHAUL_INSTANCED_PARTICLE_VERTEX_GLSL
#define OVERHAUL_INSTANCED_PARTICLE_VERTEX_GLSL

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <overhaul:packed_vertex.glsl>

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
    vertexColor = overhaulUnpackArgb(packedColor) * sample_lightmap(Sampler2, overhaulUnpackSignedShorts(packedLight));
}

#endif
