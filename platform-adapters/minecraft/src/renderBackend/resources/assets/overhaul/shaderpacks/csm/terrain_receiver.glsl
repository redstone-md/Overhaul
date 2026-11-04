layout(location = 5) in vec4 overhaulShadowedColor;
layout(location = 6) in vec3 overhaulPosition;
layout(location = 7) in float overhaulViewDepth;

layout(std140, set = 1, binding = 0) uniform CascadeReceiver {
    mat4 CascadeMatrix[4];
    vec4 CascadeSplits;
    vec4 CascadeStarts;
    vec4 CascadeTexels;
    int CascadeCount;
};
layout(set = 1, binding = 1) uniform sampler2DArrayShadow CascadeDepth;

float overhaulCascadeSample(int cascade, vec3 normal) {
    float texel = CascadeTexels[cascade];
    mat4 matrix = CascadeMatrix[cascade];
    vec4 clip = matrix * vec4(overhaulPosition + normal * texel * 1.5, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    vec2 uv = ndc.xy * 0.5 + 0.5;
    float depth = ndc.z * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || depth < 0.0 || depth > 1.0) {
        return 1.0;
    }
    float bias = texel * 0.5 * length(vec3(matrix[0][2], matrix[1][2], matrix[2][2]));
    return texture(CascadeDepth, vec4(uv, float(cascade), depth - bias));
}

float overhaulShadowVisibility() {
    vec3 normal = cross(dFdx(overhaulPosition), dFdy(overhaulPosition));
    float area = length(normal);
    normal = area > 0.0 ? normal / area : vec3(0.0);
    if (dot(normal, overhaulPosition) > 0.0) {
        normal = -normal;
    }
    int last = CascadeCount - 1;
    float viewDepth = overhaulViewDepth;
    if (viewDepth >= CascadeSplits[last]) {
        return 1.0;
    }
    int cascade = 0;
    while (cascade < last && viewDepth >= CascadeSplits[cascade]) {
        cascade++;
    }
    float visibility = overhaulCascadeSample(cascade, normal);
    if (cascade < last && viewDepth > CascadeStarts[cascade]) {
        float blend = (viewDepth - CascadeStarts[cascade]) / (CascadeSplits[cascade] - CascadeStarts[cascade]);
        visibility = mix(visibility, overhaulCascadeSample(cascade + 1, normal), blend);
    }
    return visibility;
}

