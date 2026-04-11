#version 450

layout(set = 0, binding = 0, std140) uniform SceneUniforms {
    vec4 centerRelativeAndMetrics;
    vec4 rightAndSpan;
    vec4 upAndSpan;
    vec4 forwardAndDepth;
    vec4 viewport;
} uScene;

layout(location = 0) in vec3 inPositionM;
layout(location = 1) in uint inColorArgb;
layout(location = 2) in uint inDensityWeight;

layout(location = 0) out vec4 vColor;

vec4 unpackArgb(uint argb) {
    float a = float((argb >> 24) & 0xFFu) / 255.0;
    float r = float((argb >> 16) & 0xFFu) / 255.0;
    float g = float((argb >> 8) & 0xFFu) / 255.0;
    float b = float(argb & 0xFFu) / 255.0;
    return vec4(r, g, b, a);
}

vec3 cameraRelative(vec3 worldPositionM) {
    return worldPositionM - uScene.centerRelativeAndMetrics.xyz;
}

vec2 clipXY(vec3 cameraRelativeM) {
    float halfSpanX = max(uScene.rightAndSpan.w, 1e-6);
    float halfSpanY = max(uScene.upAndSpan.w, 1e-6);
    float clipX = dot(cameraRelativeM, uScene.rightAndSpan.xyz) / halfSpanX;
    float clipY = dot(cameraRelativeM, uScene.upAndSpan.xyz) / halfSpanY;
    return vec2(clipX, -clipY);
}

float clipDepth01(vec3 cameraRelativeM) {
    float halfDepth = max(uScene.forwardAndDepth.w, 1e-6);
    float centeredDepth = dot(cameraRelativeM, uScene.forwardAndDepth.xyz);
    return clamp(0.5 + (centeredDepth / (halfDepth * 2.0)), 0.0, 1.0);
}

void main() {
    vec3 relative = cameraRelative(inPositionM);
    gl_Position = vec4(clipXY(relative), clipDepth01(relative), 1.0);

    float weight = float(max(inDensityWeight, 1u));
    gl_PointSize = clamp(weight, 1.0, max(uScene.viewport.z, 1.0));

    vec4 color = unpackArgb(inColorArgb);
    color.a *= clamp(0.55 + 0.15 * weight, 0.0, 1.0);
    vColor = color;
}
