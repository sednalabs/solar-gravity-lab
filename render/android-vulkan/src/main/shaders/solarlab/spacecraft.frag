#version 450
layout(location=0) in vec3 vNormal;
layout(location=1) in vec3 vColor;
layout(location=2) in vec3 vLight;
layout(location=3) in vec3 vView;
layout(location=4) in vec4 vMaterial;
layout(location=5) in float vPlumeFraction;
layout(location=6) flat in float vThrottle;
layout(location=7) in float vCameraDepth;
layout(location=8) flat in float vFlight;
layout(location=0) out vec4 outColor;
void main() {
    if(vFlight>0.5 && (vCameraDepth<0.1 || vCameraDepth>1.0e16)) discard;
    gl_FragDepth=vFlight>0.5 ? log2(1.0+max(vCameraDepth,0.0))/log2(1.0e16) : gl_FragCoord.z;
    if(vMaterial.w>0.5) {
        if(vThrottle<=0.001) discard;
        float fade=pow(1.0-vPlumeFraction,1.3);
        vec3 color=mix(vec3(0.72,0.90,1.0),vColor,smoothstep(0.,0.45,vPlumeFraction));
        outColor=vec4(color*1.3,fade*vThrottle*0.44);
        return;
    }
    vec3 n=normalize(vNormal),l=normalize(vLight),v=normalize(vView),h=normalize(l+v);
    if(!gl_FrontFacing) n=-n;
    float nl=max(dot(n,l),0.),nv=max(dot(n,v),0.001),nh=max(dot(n,h),0.),vh=max(dot(v,h),0.);
    float rough=clamp(vMaterial.y,0.06,1.),metal=clamp(vMaterial.x,0.,1.);
    float a=rough*rough,a2=a*a,denom=nh*nh*(a2-1.)+1.;
    float distribution=a2/(3.14159265*denom*denom);
    float k=(rough+1.)*(rough+1.)/8.;
    float geometry=(nl/(nl*(1.-k)+k))*(nv/(nv*(1.-k)+k));
    vec3 f0=mix(vec3(.04),vColor,metal);
    vec3 fresnel=f0+(1.-f0)*pow(1.-vh,5.);
    vec3 specular=distribution*geometry*fresnel/max(4.*nl*nv,.001);
    vec3 diffuse=(1.-fresnel)*(1.-metal)*vColor/3.14159265;
    // Small display fill is illustrative; incident key direction is the scene's Sun.
    vec3 color=(diffuse+specular)*nl*3.0+vColor*(.13+vMaterial.z*.8);
    color+=vColor*pow(1.-nv,3.)*.10;
    outColor=vec4(pow(color/(color+vec3(.65)),vec3(1./2.2)),1.);
}
