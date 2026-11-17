#version 450

layout(location = 0) in vec4 vColor;
layout(location = 1) flat in float vPointSizePx;
layout(location = 0) out vec4 outColor;

void main() {
    // A 1px point cannot represent a radial mask. Applying the haze to its
    // sole sample made it blink/disappear at fractional pixel coordinates.
    if (vPointSizePx <= 2.0) {
        outColor = vColor;
        return;
    }
    vec2 pointUv = gl_PointCoord * 2.0 - 1.0;
    float distanceFromCenter = length(pointUv);
    if (distanceFromCenter > 1.0) {
        discard;
    }

    float haze = 1.0 - smoothstep(0.34, 1.0, distanceFromCenter);
    vec3 boosted = min(vColor.rgb * (0.88 + 0.24 * haze), vec3(1.0));
    outColor = vec4(boosted, vColor.a * haze);
}
