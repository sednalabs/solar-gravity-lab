#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 pointUv = gl_PointCoord * 2.0 - 1.0;
    float distanceFromCenter = length(pointUv);
    if (distanceFromCenter > 1.0) {
        discard;
    }

    float disc = 1.0 - smoothstep(0.74, 1.0, distanceFromCenter);
    float core = 1.0 - smoothstep(0.0, 0.32, distanceFromCenter);
    float halo = (1.0 - smoothstep(0.42, 1.0, distanceFromCenter)) * (1.0 - core);

    vec3 boosted = min(
        vColor.rgb * (0.82 + 0.42 * core) + vec3(0.08) * halo,
        vec3(1.0)
    );
    float alpha = vColor.a * max(disc, halo * 0.22);
    outColor = vec4(boosted, alpha);
}
