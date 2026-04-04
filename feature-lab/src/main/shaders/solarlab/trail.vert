#version 450

layout(set = 0, binding = 0, std140) uniform SceneUniforms {
    vec4 centerSpan;
    vec4 metrics;
    vec4 viewport;
    vec4 tracerCounts;
} uScene;

layout(location = 0) in vec2 inPositionM;
layout(location = 1) in uint inColorArgb;
layout(location = 2) in float inAlpha;

layout(location = 0) out vec4 vColor;

vec4 unpackArgb(uint argb) {
    float a = float((argb >> 24) & 0xFFu) / 255.0;
    float r = float((argb >> 16) & 0xFFu) / 255.0;
    float g = float((argb >> 8) & 0xFFu) / 255.0;
    float b = float(argb & 0xFFu) / 255.0;
    return vec4(r, g, b, a);
}

vec2 worldToClip(vec2 worldPositionM) {
    vec2 relative = worldPositionM - uScene.centerSpan.xy;
    vec2 spans = max(uScene.centerSpan.zw, vec2(1e-6));
    vec2 clip = relative / spans;
    clip.y = -clip.y;
    return clip;
}

void main() {
    gl_Position = vec4(worldToClip(inPositionM), 0.0, 1.0);
    vec4 color = unpackArgb(inColorArgb);
    color.a *= inAlpha;
    vColor = color;
}
