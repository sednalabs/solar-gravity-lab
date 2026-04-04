#version 450

layout(set = 0, binding = 0, std140) uniform SceneUniforms {
    vec4 centerSpan;
    vec4 metrics;
    vec4 viewport;
    vec4 tracerCounts;
} uScene;

layout(location = 0) in vec3 inPositionM;
layout(location = 1) in float inRadiusM;
layout(location = 2) in uint inColorArgb;
layout(location = 3) in uint inKind;
layout(location = 4) in float inAlpha;
layout(location = 5) in float inReserved;

layout(location = 0) out vec4 vColor;

const uint KIND_STAR = 0u;
const uint KIND_PLANET = 1u;
const uint KIND_DWARF_PLANET = 2u;
const uint KIND_PROBE = 5u;
const uint KIND_TEST_OBJECT = 6u;

vec4 unpackArgb(uint argb) {
    float a = float((argb >> 24) & 0xFFu) / 255.0;
    float r = float((argb >> 16) & 0xFFu) / 255.0;
    float g = float((argb >> 8) & 0xFFu) / 255.0;
    float b = float(argb & 0xFFu) / 255.0;
    return vec4(r, g, b, a);
}

float minimumDiameterForKind(uint kind) {
    if (kind == KIND_STAR) {
        return 9.0;
    }
    if (kind == KIND_PLANET) {
        return 6.4;
    }
    if (kind == KIND_DWARF_PLANET) {
        return 5.2;
    }
    if (kind == KIND_PROBE || kind == KIND_TEST_OBJECT) {
        return 4.0;
    }
    return 3.8;
}

vec2 worldToClip(vec2 worldPositionM) {
    vec2 relative = worldPositionM - uScene.centerSpan.xy;
    vec2 spans = max(uScene.centerSpan.zw, vec2(1e-6));
    vec2 clip = relative / spans;
    clip.y = -clip.y;
    return clip;
}

void main() {
    vec2 clip = worldToClip(inPositionM.xy);
    gl_Position = vec4(clip, 0.0, 1.0);

    float metersPerPixel = max(uScene.metrics.x, 1e-6);
    float maxPointSizePx = max(uScene.metrics.z, 1.0);
    float diameterPx = max(minimumDiameterForKind(inKind), (inRadiusM / metersPerPixel) * 2.0);
    gl_PointSize = clamp(diameterPx, 1.0, maxPointSizePx);

    vec4 color = unpackArgb(inColorArgb);
    color.a *= inAlpha;
    vColor = color;
}
