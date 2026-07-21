#version 450

layout(set = 0, binding = 0, std140) uniform SceneUniforms {
    vec4 centerRelativeAndMetrics;
    vec4 rightAndSpan;
    vec4 upAndSpan;
    vec4 forwardAndDepth;
    vec4 viewport;
    vec4 primaryLightPositionRelativeAndFlags;
} uScene;

layout(location = 0) in vec3 inPositionM;
layout(location = 1) in float inVisualRadiusM;
layout(location = 2) in uint inColorArgb;
layout(location = 3) in uint inKind;
layout(location = 4) in float inAlpha;
layout(location = 5) in uint inMaterial;
layout(location = 6) in uint inAppearanceFlags;
layout(location = 7) in float inPhysicalRadiusM;
layout(location = 8) in vec3 inNorthPoleWs;
layout(location = 9) in vec3 inRingRadiiAndOpticalDepth;
layout(location = 10) in vec3 inAtmosphereRadiusDensityAndMeridian;
layout(location = 11) in vec4 inCometRadiiAndTailLengths;
layout(location = 12) in vec3 inCometAntiSolarDirectionWs;
layout(location = 13) in vec3 inCometVelocityDirectionWs;
layout(location = 14) in vec3 inRingPlaneNormalWs;

layout(location = 0) out vec4 vColor;
layout(location = 1) flat out uint vKind;
layout(location = 2) flat out vec3 vLightDirection;
layout(location = 3) out vec2 vBillboardUv;
layout(location = 4) flat out uint vMaterial;
layout(location = 5) flat out uint vAppearanceFlags;
layout(location = 6) flat out float vReferenceMeridianRadians;
layout(location = 7) flat out float vBodyRadiusUv;
layout(location = 8) flat out vec3 vNorthPoleCamera;
layout(location = 9) flat out vec3 vRingRatiosAndOpticalDepth;
layout(location = 10) flat out vec2 vAtmosphereRatioAndDensity;
layout(location = 11) flat out vec4 vCometRatios;
layout(location = 12) flat out vec3 vCometAntiSolarCamera;
layout(location = 13) flat out vec3 vCometVelocityCamera;
layout(location = 14) flat out float vSurfaceDetail;
layout(location = 15) flat out vec3 vRingPlaneCamera;

const uint KIND_STAR = 0u;
const uint KIND_PLANET = 1u;
const uint KIND_DWARF_PLANET = 2u;
const uint KIND_PROBE = 5u;
const uint KIND_TEST_OBJECT = 6u;

const vec2 BILLBOARD_CORNERS[6] = vec2[](
    vec2(-1.0, -1.0),
    vec2( 1.0, -1.0),
    vec2(-1.0,  1.0),
    vec2(-1.0,  1.0),
    vec2( 1.0, -1.0),
    vec2( 1.0,  1.0)
);

vec4 unpackArgb(uint argb) {
    float a = float((argb >> 24) & 0xFFu) / 255.0;
    float r = float((argb >> 16) & 0xFFu) / 255.0;
    float g = float((argb >> 8) & 0xFFu) / 255.0;
    float b = float(argb & 0xFFu) / 255.0;
    return vec4(r, g, b, a);
}

float minimumDiameterForKind(uint kind) {
    if (kind == KIND_STAR) {
        return 9.0;
    }
    if (kind == KIND_PLANET) {
        return 6.4;
    }
    if (kind == KIND_DWARF_PLANET) {
        return 5.2;
    }
    if (kind == KIND_PROBE || kind == KIND_TEST_OBJECT) {
        return 4.0;
    }
    return 3.8;
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

vec3 directionToCamera(vec3 worldDirection) {
    vec3 transformed = vec3(
        dot(worldDirection, uScene.rightAndSpan.xyz),
        -dot(worldDirection, uScene.upAndSpan.xyz),
        -dot(worldDirection, uScene.forwardAndDepth.xyz)
    );
    float magnitude = length(transformed);
    return magnitude > 0.0 ? transformed / magnitude : vec3(0.0, 1.0, 0.0);
}

void main() {
    vec3 relative = cameraRelative(inPositionM);
    vec2 centerClip = clipXY(relative);

    float metersPerPixel = max(uScene.centerRelativeAndMetrics.w, 1e-6);
    float visualRadiusM = max(inVisualRadiusM, max(inPhysicalRadiusM, 1.0));
    float unclampedDiameterPx = (visualRadiusM / metersPerPixel) * 2.0;
    float diameterPx = max(minimumDiameterForKind(inKind), unclampedDiameterPx);
    float maxBillboardDiameterPx = max(max(uScene.viewport.x, uScene.viewport.y) * 2.0, 1.0);
    diameterPx = clamp(diameterPx, 1.0, maxBillboardDiameterPx);

    vec2 corner = BILLBOARD_CORNERS[gl_VertexIndex];
    vec2 cornerClipOffset = corner * vec2(
        diameterPx / max(uScene.viewport.x, 1.0),
        diameterPx / max(uScene.viewport.y, 1.0)
    );
    gl_Position = vec4(centerClip + cornerClipOffset, clipDepth01(relative), 1.0);

    vec4 color = unpackArgb(inColorArgb);
    color.a *= inAlpha;
    vColor = color;
    vKind = inKind;
    vBillboardUv = corner;
    vMaterial = inMaterial;
    vAppearanceFlags = inAppearanceFlags;
    vReferenceMeridianRadians = inAtmosphereRadiusDensityAndMeridian.z;
    float physicalRatio = clamp(inPhysicalRadiusM / visualRadiusM, 0.0, 1.0);
    float minimumCoreRatio = (minimumDiameterForKind(inKind) * 0.5) / max(diameterPx, 1.0);
    vBodyRadiusUv = clamp(max(physicalRatio, minimumCoreRatio), 1e-6, 1.0);
    vNorthPoleCamera = directionToCamera(inNorthPoleWs);
    vRingRatiosAndOpticalDepth = vec3(
        inRingRadiiAndOpticalDepth.x / visualRadiusM,
        inRingRadiiAndOpticalDepth.y / visualRadiusM,
        inRingRadiiAndOpticalDepth.z
    );
    vAtmosphereRatioAndDensity = vec2(
        inAtmosphereRadiusDensityAndMeridian.x / visualRadiusM,
        inAtmosphereRadiusDensityAndMeridian.y
    );
    vCometRatios = inCometRadiiAndTailLengths / visualRadiusM;
    vCometAntiSolarCamera = directionToCamera(inCometAntiSolarDirectionWs);
    vCometVelocityCamera = directionToCamera(inCometVelocityDirectionWs);
    vRingPlaneCamera = directionToCamera(inRingPlaneNormalWs);
    float physicalDiameterPx = (max(inPhysicalRadiusM, 0.0) / metersPerPixel) * 2.0;
    vSurfaceDetail = smoothstep(7.0, 72.0, physicalDiameterPx);

    if (uScene.primaryLightPositionRelativeAndFlags.w > 0.5) {
        vec3 toLightWorld = normalize(uScene.primaryLightPositionRelativeAndFlags.xyz - inPositionM);
        vLightDirection = normalize(vec3(
            dot(toLightWorld, uScene.rightAndSpan.xyz),
            -dot(toLightWorld, uScene.upAndSpan.xyz),
            -dot(toLightWorld, uScene.forwardAndDepth.xyz)
        ));
    } else {
        vLightDirection = normalize(vec3(-0.42, -0.30, 0.86));
    }
}
