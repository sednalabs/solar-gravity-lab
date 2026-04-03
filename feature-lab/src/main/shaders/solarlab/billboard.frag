#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 pointUv = gl_PointCoord * 2.0 - 1.0;
    float distanceFromCenter = length(pointUv);
    if (distanceFromCenter > 1.0) {
        discard;
    }

    float edgeFade = 1.0 - smoothstep(0.72, 1.0, distanceFromCenter);
    float coreBoost = 1.0 - smoothstep(0.0, 0.34, distanceFromCenter);
    vec3 boosted = min(vColor.rgb * (0.84 + 0.36 * coreBoost), vec3(1.0));
    outColor = vec4(boosted, vColor.a * edgeFade);
}
