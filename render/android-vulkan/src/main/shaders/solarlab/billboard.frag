#version 450

layout(location = 0) in vec4 vColor;
layout(location = 1) flat in uint vKind;
layout(location = 2) flat in vec3 vLightDirection;
layout(location = 3) in vec2 vBillboardUv;
layout(location = 0) out vec4 outColor;

const uint KIND_STAR = 0u;

void main() {
    float distanceFromCenter = length(vBillboardUv);
    if (distanceFromCenter > 1.0) {
        discard;
    }

    if (vKind == KIND_STAR) {
        float disc = 1.0 - smoothstep(0.74, 1.0, distanceFromCenter);
        float core = 1.0 - smoothstep(0.0, 0.32, distanceFromCenter);
        float halo = (1.0 - smoothstep(0.42, 1.0, distanceFromCenter)) * (1.0 - core);
        vec3 boosted = min(
            vColor.rgb * (0.82 + 0.42 * core) + vec3(0.08) * halo,
            vec3(1.0)
        );
        float alpha = vColor.a * max(disc, halo * 0.22);
        outColor = vec4(boosted, alpha);
        return;
    }

    float sphereZ = sqrt(max(0.0, 1.0 - dot(vBillboardUv, vBillboardUv)));
    vec3 normal = normalize(vec3(vBillboardUv.x, -vBillboardUv.y, sphereZ));
    float diffuse = max(dot(normal, normalize(vLightDirection)), 0.0);
    float rim = pow(1.0 - sphereZ, 2.2);
    float edgeFade = 1.0 - smoothstep(0.94, 1.0, distanceFromCenter);
    vec3 shaded = vColor.rgb * (0.20 + 0.80 * diffuse);
    shaded += min(vColor.rgb + vec3(0.16), vec3(1.0)) * rim * 0.16;
    outColor = vec4(min(shaded, vec3(1.0)), vColor.a * edgeFade);
}
