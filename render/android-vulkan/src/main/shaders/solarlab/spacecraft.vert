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
} uScene;
layout(location=0) in vec3 inPosition;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec3 inColor;
layout(location=3) in vec4 inMaterial; // metallic, roughness, emission, effect
layout(location=4) in vec3 inCenterHigh;
layout(location=5) in vec3 inCenterLow;
layout(location=6) in vec3 inPreviousHigh;
layout(location=7) in vec3 inPreviousLow;
layout(location=8) in vec4 inAttitude;
layout(location=9) in float inThrottle;
layout(location=0) out vec3 vNormal;
layout(location=1) out vec3 vColor;
layout(location=2) out vec3 vLight;
layout(location=3) out vec3 vView;
layout(location=4) out vec4 vMaterial;
layout(location=5) out float vPlumeFraction;
layout(location=6) flat out float vThrottle;
vec3 rotateBody(vec3 p) { return p+2.0*cross(inAttitude.xyz,cross(inAttitude.xyz,p)+inAttitude.w*p); }
void main() {
    vec3 local=inPosition;
    vPlumeFraction=clamp((-local.z-4.03)/6.47,0.,1.);
    if(inMaterial.w>0.5) {
        local.z=-4.03+(local.z+4.03)*(0.18+0.82*inThrottle);
        local.xy*=0.6+0.4*inThrottle;
    }
    vec3 current=(inCenterHigh-uScene.centerRelativeAndMetrics.xyz)+(inCenterLow-uScene.cameraCenterLow.xyz);
    vec3 previous=(inPreviousHigh-uScene.centerRelativeAndMetrics.xyz)+(inPreviousLow-uScene.cameraCenterLow.xyz);
    vec3 center=mix(previous,current,uScene.presentation.x);
    vec3 relative=center+rotateBody(local);
    gl_Position=vec4(dot(relative,uScene.rightAndSpan.xyz)/uScene.rightAndSpan.w,
        -dot(relative,uScene.upAndSpan.xyz)/uScene.upAndSpan.w,
        0.5+dot(relative,uScene.forwardAndDepth.xyz)/(2.0*uScene.forwardAndDepth.w),1.0);
    vNormal=rotateBody(inNormal);
    vColor=inColor;
    vMaterial=inMaterial;
    vView=-uScene.forwardAndDepth.xyz;
    vec3 light=uScene.primaryLightPositionRelativeAndFlags.xyz-(inCenterHigh+inCenterLow);
    vLight=uScene.primaryLightPositionRelativeAndFlags.w>0.5?normalize(light):normalize(vec3(-0.4,0.6,0.7));
    vThrottle=inThrottle;
}
