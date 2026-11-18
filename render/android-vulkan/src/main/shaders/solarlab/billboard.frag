#version 450

layout(set=0,binding=0,std140) uniform SceneUniforms {
    vec4 centerRelativeAndMetrics;
    vec4 rightAndSpan;
    vec4 upAndSpan;
    vec4 forwardAndDepth;
    vec4 viewport;
    vec4 primaryLightPositionRelativeAndFlags;
    vec4 presentation;
    vec4 trailPresentationTranslation;
    vec4 cameraCenterLow;
    vec4 flightProjection;
} uScene;


layout(set = 0, binding = 1, std430) readonly buffer SurfaceColors {
    uint rgba[];
} uSurface;

layout(location = 0) in vec4 vColor;
layout(location = 1) flat in uint vKind;
layout(location = 2) flat in vec3 vLightDirection;
layout(location = 3) in vec2 vBillboardUv;
layout(location = 4) flat in uint vMaterial;
layout(location = 5) flat in uint vAppearanceFlags;
layout(location = 6) flat in vec4 vSurfaceFrame;
layout(location = 7) flat in float vBodyRadiusUv;
layout(location = 8) flat in vec3 vNorthPoleCamera;
layout(location = 9) flat in vec3 vRingRatiosAndOpticalDepth;
layout(location = 10) flat in vec2 vAtmosphereRatioAndDensity;
layout(location = 11) flat in vec4 vCometRatios;
layout(location = 12) flat in vec3 vCometAntiSolarCamera;
layout(location = 13) flat in vec3 vCometVelocityCamera;
layout(location = 14) flat in vec3 vSurfaceMetrics;
layout(location = 15) flat in vec3 vRingPlaneCamera;
layout(location = 16) flat in vec4 vPerspectiveSphere;
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
const uint HAS_EARTH_MAP = 1u << 16u;
const uint HAS_MOON_MAP = 1u << 17u;

#define vSurfaceDetail vSurfaceMetrics.x
#define vReferenceMeridianRadians vSurfaceFrame.w

float hash21(vec2 point) {
    vec3 p3 = fract(vec3(point.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 bodyLocalNormal(vec3 cameraNormal) {
    vec3 pole = normalize(vNorthPoleCamera);
    vec3 reference = abs(pole.z) < 0.92 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    // The map's meridian belongs to the body, so orbiting the camera reveals
    // different geography rather than turning the map to face the viewer.
    bool mapped = (vAppearanceFlags & (HAS_EARTH_MAP | HAS_MOON_MAP)) != 0u;
    vec3 east = mapped ? normalize(vSurfaceFrame.xyz) : normalize(cross(reference, pole));
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

vec3 surfaceTexel(int map, ivec2 pixel) {
    int x = (pixel.x % 1024 + 1024) % 1024;
    int y = clamp(pixel.y, 0, 511);
    return unpackUnorm4x8(uSurface.rgba[map * 524288 + y * 1024 + x]).rgb;
}

vec3 mappedSurface(int map, vec3 local) {
    // Equirectangular maps: Greenwich at the centre, north at the top. Wrapping
    // longitude and clamping latitude keep bilinear samples continuous at seams/poles.
    vec2 uv = vec2(atan(local.z, local.x) / 6.28318530718 + 0.5,
        0.5 - asin(clamp(local.y, -1.0, 1.0)) / 3.14159265359);
    vec2 pixel = uv * vec2(1024.0, 512.0) - 0.5;
    ivec2 base = ivec2(floor(pixel));
    vec2 fraction = fract(pixel);
    return mix(mix(surfaceTexel(map, base), surfaceTexel(map, base + ivec2(1, 0)), fraction.x),
        mix(surfaceTexel(map, base + ivec2(0, 1)), surfaceTexel(map, base + ivec2(1, 1)), fraction.x),
        fraction.y);
}

vec3 surfaceColor(vec3 normal, float sphereZ) {
    vec3 local = bodyLocalNormal(normal);
    if ((vAppearanceFlags & HAS_EARTH_MAP) != 0u) {
        vec3 mapped = mappedSurface(0, local);
        float luminance = dot(mapped, vec3(0.2126, 0.7152, 0.0722));
        float blueDominance = mapped.b - max(mapped.r, mapped.g);
        float darkOcean = smoothstep(0.025, 0.10, blueDominance) *
            (1.0 - smoothstep(0.12, 0.35, luminance));
        // Illustrative display colour: reveal the map's dark blue ocean without
        // tinting neutral terrain or bright ice. Geography and the source asset stay intact.
        return mix(mapped, max(mapped, vec3(0.035, 0.22, 0.46)), darkOcean * 0.85);
    }
    if ((vAppearanceFlags & HAS_MOON_MAP) != 0u) return mappedSurface(1, local);
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
    float sunFacing = dot(normal, normalize(vLightDirection));
    float diffuse = max(sunFacing, 0.0);
    float rim = pow(1.0 - sphereZ, 2.2);
    vec3 shaded = color * (0.16 + 0.84 * diffuse);
    if ((vAppearanceFlags & HAS_EARTH_MAP) != 0u) {
        // A bounded, monotonic Earth-only tone curve keeps dark regions readable.
        // The Sun's diffuse term and terminator remain unchanged; zero and full
        // white remain fixed, and the existing limb contribution is added below.
        shaded = pow(clamp(shaded, vec3(0.0), vec3(1.0)), vec3(0.72));
        // An illustrative thin blue limb follows the actual Sun. It must not turn
        // the night-side edge into an equally illuminated ring.
        shaded += vec3(0.08, 0.24, 0.42) * rim * smoothstep(-0.08, 0.18, sunFacing) * 0.35;
    } else {
        shaded += min(color + vec3(0.18), vec3(1.0)) * rim * 0.15;
    }
    return min(shaded, vec3(1.0));
}

bool sphereRay(vec3 ray,vec3 center,float radius,out float distance) {
    // Camera-space +Z points into the scene. The camera is at the ray origin.
    float along=dot(ray,center);
    vec3 perpendicular=center-ray*along;
    float discriminant=radius*radius-dot(perpendicular,perpendicular);
    if(discriminant<0.0) return false;
    float root=sqrt(max(discriminant,0.0));
    distance=along-root;
    if(distance<uScene.flightProjection.z) distance=along+root;
    return distance>=uScene.flightProjection.z;
}

void perspectiveBody() {
    vec2 ndc=2.0*gl_FragCoord.xy/uScene.viewport.xy-1.0;
    vec2 tangents=vec2(uScene.rightAndSpan.w,uScene.upAndSpan.w)/uScene.flightProjection.y;
    vec3 ray=normalize(vec3(ndc.x*tangents.x,-ndc.y*tangents.y,1.0));
    float radius=max(vPerspectiveSphere.w,0.0);
    if(radius<=0.0) discard;
    vec3 center=vPerspectiveSphere.xyz;
    float distance;
    if(sphereRay(ray,center,radius,distance)) {
        if(distance*ray.z>uScene.flightProjection.w) discard;
        vec3 outward=normalize(ray*distance-center);
        // Surface appearance uses right/up/toward-viewer, unlike +Z camera depth.
        vec3 normal=vec3(outward.xy,-outward.z);
        float facing=max(dot(-ray,outward),0.0);
        vec3 color=(vKind==KIND_STAR || vMaterial==MATERIAL_STELLAR)
            ? vec3(1.0,0.83,0.54) : litSurface(normal,surfaceColor(normal,facing),facing);
        if((vAppearanceFlags & HAS_ATMOSPHERE)!=0u) {
            float sunlight=smoothstep(-0.16,0.28,dot(normal,normalize(vLightDirection)));
            color+=vec3(0.10,0.34,0.68)*pow(1.0-facing,4.0)*sunlight*0.45;
        }
        outColor=vec4(min(color,vec3(1.0)),vColor.a);
        gl_FragDepth=log2(1.0+distance*ray.z)/log2(uScene.flightProjection.w);
        return;
    }
    float atmosphereRadius=radius*vAtmosphereRatioAndDensity.x/max(vBodyRadiusUv,1e-9);
    if((vAppearanceFlags & HAS_ATMOSPHERE)!=0u && atmosphereRadius>radius &&
       sphereRay(ray,center,atmosphereRadius,distance)) {
        if(distance*ray.z>uScene.flightProjection.w) discard;
        float closest=length(center-ray*dot(center,ray));
        float shell=1.0-smoothstep(radius,atmosphereRadius,closest);
        vec3 outward=normalize(ray*distance-center);
        float sunlight=smoothstep(-0.16,0.28,dot(vec3(outward.xy,-outward.z),normalize(vLightDirection)));
        outColor=vec4(0.15,0.45,0.92,shell*sunlight*0.42);
        gl_FragDepth=log2(1.0+distance*ray.z)/log2(uScene.flightProjection.w);
        return;
    }
    discard;
}

void main() {
    if(uScene.flightProjection.x>0.5) { perspectiveBody(); return; }

    vec2 cameraUv = vec2(vBillboardUv.x, -vBillboardUv.y);
    float distanceFromCenter = length(cameraUv);
    // Derivatives are evaluated before divergent surface/effect branches. A close
    // Earth gets a pixel-wide silhouette, not a 6%-of-radius translucent fringe.
    float silhouettePixelWidth = max(fwidth(distanceFromCenter), 1e-6);
    bool mappedEarth = (vAppearanceFlags & HAS_EARTH_MAP) != 0u;
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
        float atmosphereAlpha = shell * outerFade * density * 0.28;
        if (mappedEarth && atmosphereRadius > vBodyRadiusUv) {
            vec3 limbNormal = vec3(cameraUv / max(distanceFromCenter, 1e-6), 0.0);
            float sunlight = smoothstep(-0.12, 0.25, dot(limbNormal, normalize(vLightDirection)));
            float surfaceEdge = smoothstep(vBodyRadiusUv - silhouettePixelWidth, vBodyRadiusUv, distanceFromCenter);
            float altitudeFade = 1.0 - smoothstep(vBodyRadiusUv, atmosphereRadius, distanceFromCenter);
            atmosphereAlpha = surfaceEdge * altitudeFade * density * sunlight * 0.35;
        }
        composite(accumulated, atmosphereColor, atmosphereAlpha);
    }

    bool insideBody = distanceFromCenter <= vBodyRadiusUv;
    float bodyZ = insideBody
        ? sqrt(max(0.0, vBodyRadiusUv * vBodyRadiusUv - dot(cameraUv, cameraUv)))
        : 0.0;

    bool hasRing = (vAppearanceFlags & HAS_RING_SYSTEM) != 0u;
    bool insideRing = false;
    bool ringInFront = false;
    float ringZ = 0.0;
    float ringAlpha = 0.0;
    vec3 ringColor = vec3(0.82, 0.70, 0.50);
    if (hasRing) {
        vec3 ringNormal = normalize(vRingPlaneCamera);
        float normalZ = abs(ringNormal.z) < 0.055
            ? (ringNormal.z < 0.0 ? -0.055 : 0.055)
            : ringNormal.z;
        ringZ = -(ringNormal.x * cameraUv.x + ringNormal.y * cameraUv.y) / normalZ;
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
        float edgeFade = mappedEarth
            ? 1.0 - smoothstep(vBodyRadiusUv - silhouettePixelWidth, vBodyRadiusUv, distanceFromCenter)
            : 1.0 - smoothstep(0.94, 1.0, length(sphereUv));
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
    float frontSurfaceZ = insideRing && ringInFront ? ringZ : bodyZ;
    float surfaceDepth = vSurfaceMetrics.y - frontSurfaceZ * vSurfaceMetrics.z;
    if (surfaceDepth < 0.0 || surfaceDepth > 1.0) {
        discard;
    }
    gl_FragDepth = surfaceDepth;
    outColor = accumulated;
}
