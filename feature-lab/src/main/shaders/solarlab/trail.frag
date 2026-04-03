#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(min(vColor.rgb * 1.15, vec3(1.0)), vColor.a);
}
