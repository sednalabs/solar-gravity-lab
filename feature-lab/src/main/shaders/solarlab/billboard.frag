#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 pointUv = gl_PointCoord * 2.0 - 1.0;
    float distanceFromCenter = length(pointUv);
    if (distanceFromCenter > 1.0) {
        discard;
    }

    float edgeFade = 1.0 - smoothstep(0.78, 1.0, distanceFromCenter);
    outColor = vec4(vColor.rgb, vColor.a * edgeFade);
}
