#ifndef OVERHAUL_MODEL_QUAD_INPUTS_GLSL
#define OVERHAUL_MODEL_QUAD_INPUTS_GLSL

#include <overhaul:packed_vertex.glsl>

layout(location = 0) in vec2 Corner;
layout(location = 1) in vec4 InstanceRow0;
layout(location = 2) in vec4 InstanceRow1;
layout(location = 3) in vec4 InstanceRow2;
layout(location = 4) in vec3 InstanceMin;
layout(location = 5) in vec3 InstanceMax;
layout(location = 6) in vec4 InstanceUv01;
layout(location = 7) in vec4 InstanceUv23;
layout(location = 8) in uvec4 InstanceMaterial;
layout(location = 9) in uint InstanceEncoding;

vec3 Position;
vec4 Color;
vec2 UV0;
ivec2 UV1;
ivec2 UV2;
vec3 Normal;

float overhaulTransformRow(vec4 row, vec3 position, bool fused) {
    if (fused) {
        return fma(row.x, position.x, fma(row.y, position.y, fma(row.z, position.z, row.w)));
    }
    // JOML's default fma helper uses separately rounded products/additions, right nested.
    precise float zProduct = row.z * position.z;
    precise float zSum = zProduct + row.w;
    precise float yProduct = row.y * position.y;
    precise float ySum = yProduct + zSum;
    precise float xProduct = row.x * position.x;
    precise float result = xProduct + ySum;
    return result;
}

void overhaulDecodeModelQuad() {
    uint vertex = Corner.x > 0.0 ? (Corner.y < 0.0 ? 0u : 1u) : (Corner.y > 0.0 ? 2u : 3u);
    uint mask = (InstanceEncoding >> (vertex * 3u)) & 7u;
    vec3 local = vec3((mask & 1u) != 0u ? InstanceMax.x : InstanceMin.x,
                      (mask & 2u) != 0u ? InstanceMax.y : InstanceMin.y,
                      (mask & 4u) != 0u ? InstanceMax.z : InstanceMin.z);
    if ((InstanceEncoding & 4096u) != 0u) {
        Position = local;
    } else if ((InstanceEncoding & 8192u) != 0u) {
        Position = local + vec3(InstanceRow0.w, InstanceRow1.w, InstanceRow2.w);
    } else {
        bool fused = (InstanceEncoding & 16384u) != 0u;
        Position = vec3(overhaulTransformRow(InstanceRow0, local, fused),
                        overhaulTransformRow(InstanceRow1, local, fused),
                        overhaulTransformRow(InstanceRow2, local, fused));
    }
    UV0 = vertex == 0u ? InstanceUv01.xy : vertex == 1u ? InstanceUv01.zw
                       : vertex == 2u ? InstanceUv23.xy : InstanceUv23.zw;
    Normal = overhaulUnpackNormal(InstanceMaterial.x);
    Color = overhaulUnpackArgb(InstanceMaterial.y);
    UV2 = overhaulUnpackSignedShorts(InstanceMaterial.z);
    UV1 = overhaulUnpackSignedShorts(InstanceMaterial.w);
}

#endif
