#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 pointUv = gl_PointCoord * 2.0 - 1.0;
    float distanceFromCenter = length(pointUv);
    if (distanceFromCenter > 1.0) {
        discard;
    }

    float haze = 1.0 - smoothstep(0.34, 1.0, distanceFromCenter);
    vec3 boosted = min(vColor.rgb * (0.88 + 0.24 * haze), vec3(1.0));
    outColor = vec4(boosted, vColor.a * haze);
}
