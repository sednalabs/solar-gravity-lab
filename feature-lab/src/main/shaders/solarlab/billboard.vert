#version 450

layout(set = 0, binding = 0, std140) uniform SceneUniforms {
    vec4 centerSpan;
    vec4 metrics;
    vec4 viewport;
} uScene;

layout(location = 0) in vec3 inPositionM;
layout(location = 1) in float inRadiusM;
layout(location = 2) in uint inColorArgb;
layout(location = 3) in uint inKind;
layout(location = 4) in float inAlpha;
layout(location = 5) in float inReserved;

layout(location = 0) out vec4 vColor;

vec4 unpackArgb(uint argb) {
    float a = float((argb >> 24) & 0xFFu) / 255.0;
    float r = float((argb >> 16) & 0xFFu) / 255.0;
    float g = float((argb >> 8) & 0xFFu) / 255.0;
    float b = float(argb & 0xFFu) / 255.0;
    return vec4(r, g, b, a);
}

float minimumDiameterForKind(uint kind) {
    if (kind == 0u) {
        return 8.0;
    }
    if (kind == 1u) {
        return 5.6;
    }
    if (kind == 2u) {
        return 4.6;
    }
    if (kind == 5u || kind == 6u) {
        return 3.2;
    }
    return 3.4;
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
