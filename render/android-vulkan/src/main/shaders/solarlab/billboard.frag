#version 450

layout(location = 0) in vec4 vColor;
layout(location = 1) flat in uint vKind;
layout(location = 2) flat in vec3 vLightDirection;
layout(location = 3) in vec2 vBillboardUv;
layout(location = 4) flat in uint vMaterial;
layout(location = 5) flat in uint vAppearanceFlags;
layout(location = 6) flat in float vReferenceMeridianRadians;
layout(location = 7) flat in float vBodyRadiusUv;
layout(location = 8) flat in vec3 vNorthPoleCamera;
layout(location = 9) flat in vec3 vRingRatiosAndOpticalDepth;
layout(location = 10) flat in vec2 vAtmosphereRatioAndDensity;
layout(location = 11) flat in vec4 vCometRatios;
layout(location = 12) flat in vec3 vCometAntiSolarCamera;
layout(location = 13) flat in vec3 vCometVelocityCamera;
layout(location = 14) flat in float vSurfaceDetail;
layout(location = 15) flat in vec3 vRingPlaneCamera;
layout(location = 0) out vec4 outColor;

const uint KIND_STAR = 0u;
const uint MATERIAL_STELLAR = 0u;
const uint MATERIAL_TERRESTRIAL = 1u;
const uint MATERIAL_ROCKY = 2u;
const uint MATERIAL_GAS_GIANT = 3u;
const uint MATERIAL_ICE_GIANT = 4u;
const uint MATERIAL_ICY = 5u;
const uint MATERIAL_LUNAR = 6u;
const uint MATERIAL_ASTEROID = 7u;
const uint MATERIAL_COMET = 8u;
const uint HAS_RING_SYSTEM = 1u << 0u;
const uint HAS_ATMOSPHERE = 1u << 1u;
const uint HAS_COMET = 1u << 2u;

float hash21(vec2 point) {
    vec3 p3 = fract(vec3(point.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 bodyLocalNormal(vec3 cameraNormal) {
    vec3 pole = normalize(vNorthPoleCamera);
    vec3 reference = abs(pole.z) < 0.92 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 east = normalize(cross(reference, pole));
    vec3 meridian = normalize(cross(pole, east));
    vec3 local = vec3(
        dot(cameraNormal, east),
        dot(cameraNormal, pole),
        dot(cameraNormal, meridian)
    );
    float phaseCos = cos(vReferenceMeridianRadians);
    float phaseSin = sin(vReferenceMeridianRadians);
    return vec3(
        phaseCos * local.x - phaseSin * local.z,
        local.y,
        phaseSin * local.x + phaseCos * local.z
    );
}

float layeredSurfaceNoise(vec3 local) {
    float broad = sin(local.x * 5.1 + local.y * 2.3) *
        sin(local.z * 4.7 - local.x * 1.9);
    float medium = sin(local.x * 11.3 - local.z * 7.1 + local.y * 3.7) *
        sin(local.y * 9.2 + local.z * 5.9);
    float fine = sin(local.x * 23.0 + local.y * 17.0 - local.z * 13.0);
    return clamp(0.50 + broad * 0.22 + medium * 0.16 + fine * 0.08, 0.0, 1.0);
}

void composite(inout vec4 accumulated, vec3 color, float alpha) {
    alpha = clamp(alpha, 0.0, 1.0);
    float combinedAlpha = alpha + accumulated.a * (1.0 - alpha);
    vec3 premultiplied = color * alpha + accumulated.rgb * accumulated.a * (1.0 - alpha);
    accumulated.rgb = combinedAlpha > 0.0 ? premultiplied / combinedAlpha : vec3(0.0);
    accumulated.a = combinedAlpha;
}

vec3 surfaceColor(vec3 normal, float sphereZ) {
    vec3 local = bodyLocalNormal(normal);
    float latitude = local.y;
    float longitude = atan(local.z, local.x);
    float broadNoise = layeredSurfaceNoise(local);
    float fineNoise = layeredSurfaceNoise(local.zxy * 2.13 + vec3(0.17, -0.31, 0.23));
    float detail = mix(0.5, 1.0, vSurfaceDetail);

    if (vMaterial == MATERIAL_TERRESTRIAL) {
        if (vAtmosphereRatioAndDensity.y > 1.25) {
            float cloudBands = 0.5 + 0.5 * sin(latitude * 34.0 + broadNoise * 3.0);
            return mix(vec3(0.70, 0.36, 0.10), vec3(0.98, 0.77, 0.36), cloudBands * detail);
        }
        float continentSignal = broadNoise * 0.72 + fineNoise * 0.28;
        float land = smoothstep(0.48, 0.61, continentSignal);
        vec3 ocean = mix(vec3(0.025, 0.12, 0.30), vec3(0.06, 0.34, 0.66), sphereZ);
        vec3 terrain = mix(vec3(0.12, 0.32, 0.10), vec3(0.58, 0.45, 0.22), fineNoise);
        vec3 result = mix(ocean, terrain, land * detail);
        float cloudNoise = layeredSurfaceNoise(local.yzx * 3.1 + vec3(-0.4, 0.2, 0.7));
        float cloud = smoothstep(0.66, 0.82, cloudNoise);
        return mix(result, vec3(0.92), cloud * 0.22 * detail);
    }
    if (vMaterial == MATERIAL_GAS_GIANT) {
        float bands = 0.5 + 0.5 * sin(latitude * 31.0 + sin(longitude * 2.0) * 0.55);
        float fineBands = 0.5 + 0.5 * sin(latitude * 67.0 - longitude * 0.8);
        float belts = smoothstep(0.20, 0.80, bands) * 0.75 + fineBands * 0.25;
        vec3 cream = vec3(0.88, 0.72, 0.47);
        vec3 ochre = vec3(0.48, 0.25, 0.12);
        vec3 result = mix(cream, ochre, (0.14 + belts * 0.38) * detail);
        vec2 stormOffset = vec2(longitude - 0.65, (latitude + 0.24) * 2.4);
        float storm = exp(-18.0 * dot(stormOffset, stormOffset));
        return mix(result, vec3(0.72, 0.18, 0.08), storm * 0.72 * detail);
    }
    if (vMaterial == MATERIAL_ICE_GIANT) {
        float bands = 0.5 + 0.5 * sin(latitude * 20.0 + sin(longitude) * 0.45);
        return mix(vec3(0.18, 0.56, 0.72), vec3(0.50, 0.86, 0.91), (0.18 + bands * 0.25) * detail);
    }
    if (vMaterial == MATERIAL_ROCKY || vMaterial == MATERIAL_LUNAR ||
        vMaterial == MATERIAL_ASTEROID || vMaterial == MATERIAL_ICY ||
        vMaterial == MATERIAL_COMET) {
        vec2 cellUv = vec2(longitude / 3.14159265, latitude) * mix(9.0, 22.0, detail);
        vec2 cell = floor(cellUv);
        vec2 within = fract(cellUv) - 0.5;
        float craterSeed = hash21(cell);
        float craterRadius = mix(0.10, 0.34, craterSeed);
        float crater = 1.0 - smoothstep(craterRadius * 0.72, craterRadius, length(within));
        vec3 low;
        vec3 high;
        if (vMaterial == MATERIAL_ROCKY) {
            low = vec3(0.30, 0.14, 0.08);
            high = vec3(0.72, 0.34, 0.16);
        } else if (vMaterial == MATERIAL_ICY) {
            low = vec3(0.38, 0.48, 0.57);
            high = vec3(0.82, 0.88, 0.91);
        } else if (vMaterial == MATERIAL_COMET) {
            low = vec3(0.08, 0.09, 0.10);
            high = vec3(0.34, 0.36, 0.38);
        } else {
            low = vec3(0.18, 0.19, 0.20);
            high = vec3(0.62, 0.61, 0.58);
        }
        vec3 terrain = mix(low, high, broadNoise * 0.62 + fineNoise * 0.38);
        return terrain * mix(1.0, 0.58, crater * detail);
    }
    return vColor.rgb;
}

vec3 litSurface(vec3 normal, vec3 color, float sphereZ) {
    float diffuse = max(dot(normal, normalize(vLightDirection)), 0.0);
    float rim = pow(1.0 - sphereZ, 2.2);
    vec3 shaded = color * (0.16 + 0.84 * diffuse);
    shaded += min(color + vec3(0.18), vec3(1.0)) * rim * 0.15;
    return min(shaded, vec3(1.0));
}

void main() {
    vec2 cameraUv = vec2(vBillboardUv.x, -vBillboardUv.y);
    float distanceFromCenter = length(cameraUv);
    vec4 accumulated = vec4(0.0);

    bool hasComet = (vAppearanceFlags & HAS_COMET) != 0u;
    if (hasComet) {
        vec2 tailAxis = vCometAntiSolarCamera.xy;
        float tailAxisLength = length(tailAxis);
        tailAxis = tailAxisLength > 0.0001 ? tailAxis / tailAxisLength : vec2(1.0, 0.0);
        vec2 velocityAxis = length(vCometVelocityCamera.xy) > 0.0001
            ? normalize(vCometVelocityCamera.xy)
            : tailAxis;
        vec2 dustAxis = normalize(mix(tailAxis, velocityAxis, 0.12));
        vec2 dustPerpendicular = vec2(-dustAxis.y, dustAxis.x);
        vec2 ionPerpendicular = vec2(-tailAxis.y, tailAxis.x);
        float dustAxialDistance = dot(cameraUv, dustAxis);
        float dustLateralDistance = abs(dot(cameraUv, dustPerpendicular));
        float ionAxialDistance = dot(cameraUv, tailAxis);
        float ionLateralDistance = abs(dot(cameraUv, ionPerpendicular));
        float dustLength = clamp(vCometRatios.z, 0.0, 1.0);
        float ionLength = clamp(vCometRatios.w, 0.0, 1.0);
        float comaRadius = max(vCometRatios.y, vBodyRadiusUv * 3.2);
        if (dustAxialDistance >= 0.0 && dustAxialDistance <= dustLength && dustLength > 0.0) {
            float progress = dustAxialDistance / dustLength;
            float width = comaRadius * 0.55 + progress * dustLength * 0.085;
            float dustAlpha = (1.0 - smoothstep(width * 0.35, width, dustLateralDistance)) *
                pow(1.0 - progress, 1.4) * 0.42;
            composite(accumulated, vec3(0.82, 0.76, 0.63), dustAlpha);
        }
        if (ionAxialDistance >= 0.0 && ionAxialDistance <= ionLength && ionLength > 0.0) {
            float progress = ionAxialDistance / ionLength;
            float width = comaRadius * 0.28 + progress * ionLength * 0.018;
            float ionAlpha = (1.0 - smoothstep(width * 0.24, width, ionLateralDistance)) *
                pow(1.0 - progress, 1.1) * 0.48;
            composite(accumulated, vec3(0.32, 0.70, 1.0), ionAlpha);
        }
        float comaAlpha = exp(-3.6 * distanceFromCenter * distanceFromCenter /
            max(comaRadius * comaRadius, 1e-8)) * 0.58;
        composite(accumulated, vec3(0.68, 0.88, 1.0), comaAlpha);
    }

    bool hasAtmosphere = (vAppearanceFlags & HAS_ATMOSPHERE) != 0u;
    float atmosphereRadius = max(vAtmosphereRatioAndDensity.x, vBodyRadiusUv);
    if (hasAtmosphere && distanceFromCenter <= atmosphereRadius) {
        float shell = smoothstep(vBodyRadiusUv * 0.86, atmosphereRadius, distanceFromCenter);
        float outerFade = 1.0 - smoothstep(atmosphereRadius * 0.82, atmosphereRadius, distanceFromCenter);
        float density = clamp(vAtmosphereRatioAndDensity.y, 0.0, 2.0);
        vec3 atmosphereColor = vMaterial == MATERIAL_TERRESTRIAL && density > 1.25
            ? vec3(1.0, 0.58, 0.20)
            : vec3(0.24, 0.62, 1.0);
        composite(accumulated, atmosphereColor, shell * outerFade * density * 0.28);
    }

    bool insideBody = distanceFromCenter <= vBodyRadiusUv;
    float bodyZ = insideBody
        ? sqrt(max(0.0, vBodyRadiusUv * vBodyRadiusUv - dot(cameraUv, cameraUv)))
        : 0.0;

    bool hasRing = (vAppearanceFlags & HAS_RING_SYSTEM) != 0u;
    bool insideRing = false;
    bool ringInFront = false;
    float ringAlpha = 0.0;
    vec3 ringColor = vec3(0.82, 0.70, 0.50);
    if (hasRing) {
        vec3 ringNormal = normalize(vRingPlaneCamera);
        float normalZ = abs(ringNormal.z) < 0.055
            ? (ringNormal.z < 0.0 ? -0.055 : 0.055)
            : ringNormal.z;
        float ringZ = -(ringNormal.x * cameraUv.x + ringNormal.y * cameraUv.y) / normalZ;
        float ringRadius = length(vec3(cameraUv, ringZ));
        float innerRadius = max(vRingRatiosAndOpticalDepth.x, vBodyRadiusUv);
        float outerRadius = max(vRingRatiosAndOpticalDepth.y, innerRadius + 1e-5);
        float edgeWidth = max((outerRadius - innerRadius) * 0.035, 0.0015);
        float innerEdge = smoothstep(innerRadius - edgeWidth, innerRadius + edgeWidth, ringRadius);
        float outerEdge = 1.0 - smoothstep(outerRadius - edgeWidth, outerRadius + edgeWidth, ringRadius);
        float ringSpan = max(outerRadius - innerRadius, 1e-5);
        float radialPosition = clamp((ringRadius - innerRadius) / ringSpan, 0.0, 1.0);
        float broadBands = 0.5 + 0.5 * sin(radialPosition * 31.4159265 + 0.8);
        float fineBands = 0.5 + 0.5 * sin(radialPosition * 69.1150384 - 0.35);
        float divisions = broadBands * 0.72 + fineBands * 0.28;
        insideRing = innerEdge * outerEdge > 0.001;
        ringAlpha = innerEdge * outerEdge * clamp(vRingRatiosAndOpticalDepth.z, 0.0, 1.0) *
            mix(0.58, 0.88, divisions);
        ringColor = mix(vec3(0.48, 0.38, 0.27), vec3(0.91, 0.82, 0.65), divisions);
        ringInFront = !insideBody || ringZ > bodyZ;
        if (insideRing && !ringInFront) {
            composite(accumulated, ringColor, ringAlpha);
        }
    }

    if (insideBody) {
        vec2 sphereUv = cameraUv / max(vBodyRadiusUv, 1e-6);
        float sphereZ = sqrt(max(0.0, 1.0 - dot(sphereUv, sphereUv)));
        vec3 normal = normalize(vec3(sphereUv, sphereZ));
        float edgeFade = 1.0 - smoothstep(0.94, 1.0, length(sphereUv));
        if (vKind == KIND_STAR || vMaterial == MATERIAL_STELLAR) {
            float core = 1.0 - smoothstep(0.0, 0.34, length(sphereUv));
            float halo = 1.0 - smoothstep(0.58, 1.0, length(sphereUv));
            vec3 stellar = min(vec3(1.0, 0.64, 0.20) * (0.92 + core * 0.34) + vec3(0.24) * halo, vec3(1.0));
            composite(accumulated, stellar, max(edgeFade, halo * 0.26) * vColor.a);
        } else {
            vec3 materialColor = surfaceColor(normal, sphereZ);
            composite(accumulated, litSurface(normal, materialColor, sphereZ), edgeFade * vColor.a);
        }
    }

    if (insideRing && ringInFront) {
        composite(accumulated, ringColor, ringAlpha);
    }

    if (accumulated.a <= 0.001) {
        discard;
    }
    outColor = accumulated;
}
