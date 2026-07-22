#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 boosted = min(vColor.rgb * 1.18 + vec3(0.015), vec3(1.0));
    outColor = vec4(boosted, clamp(vColor.a * 0.98, 0.0, 1.0));
}
