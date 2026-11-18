// Presentation only. Positions, attitude, trajectories and thrust come from Rust.
export const add=(a,b)=>a.map((v,i)=>v+b[i]);
export const sub=(a,b)=>a.map((v,i)=>v-b[i]);
export const scale=(a,s)=>a.map(v=>v*s);
export const dot=(a,b)=>a.reduce((s,v,i)=>s+v*b[i],0);
export const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
export const unit=a=>{const n=Math.hypot(...a);return n>1e-12?scale(a,1/n):[0,0,1];};
export function rotate(q,v){const t=scale(cross([q.x,q.y,q.z],v),2);return add(v,add(scale(t,q.w),cross([q.x,q.y,q.z],t)));}

const common=`
uniform vec3 uRight,uUp,uForward,uEye;
uniform float uAspect,uTan;
vec4 project(vec3 p){float z=dot(p,uForward);return vec4(dot(p,uRight)/(uTan*uAspect),dot(p,uUp)/uTan,z-.2,z);}
vec3 qrotate(vec4 q,vec3 p){vec3 t=2.*cross(q.xyz,p);return p+q.w*t+cross(q.xyz,t);}
`;
const meshVertex=`#version 300 es
precision highp float;
layout(location=0)in vec3 aPosition;layout(location=1)in vec3 aNormal;layout(location=2)in vec4 aColor;
uniform vec4 uAttitude;uniform vec3 uPosition;uniform float uScale;
out vec3 normal;out vec4 color;out float depth;${common}
void main(){vec3 p=qrotate(uAttitude,aPosition*uScale)+uPosition-uEye;normal=qrotate(uAttitude,aNormal);color=aColor;depth=dot(p,uForward);gl_Position=project(p);}
`;
const meshFragment=`#version 300 es
precision highp float;in vec3 normal;in vec4 color;in float depth;uniform vec3 uSun;uniform float uEmission;out vec4 pixel;
void main(){if(depth<.1)discard;vec3 n=normalize(normal);float light=max(dot(n,normalize(uSun)),0.);vec3 c=color.rgb*(.19+.81*light);c+=color.rgb*uEmission;pixel=vec4(pow(max(c,vec3(0.)),vec3(.78)),color.a);gl_FragDepth=log2(1.+depth)/log2(1e13);}
`;
const planetVertex=`#version 300 es
layout(location=0)in vec2 p;out vec2 uv;void main(){uv=p;gl_Position=vec4(p,0,1);}`;
const planetFragment=`#version 300 es
precision highp float;in vec2 uv;out vec4 pixel;
uniform vec3 uCenter,uSun,uPole;uniform float uRadius,uAtmosphere,uMeridian;uniform int uKind;uniform sampler2D uTexture;${common}
bool hit(vec3 ray,float radius,out float distance){float along=dot(ray,uCenter);vec3 side=uCenter-ray*along;float d=radius*radius-dot(side,side);if(d<0.)return false;distance=along-sqrt(max(d,0.));if(distance<.1)distance=along+sqrt(max(d,0.));return distance>=.1;}
void main(){vec3 ray=normalize(uForward+uRight*uv.x*uTan*uAspect+uUp*uv.y*uTan);float distance;
if(hit(ray,uRadius,distance)){vec3 n=normalize(ray*distance-uCenter);float facing=max(dot(n,-ray),0.);float sunlight=dot(n,normalize(uSun));
if(uKind==2){pixel=vec4(1.,.83,.52,1.);}else{
vec3 pole=normalize(uPole);vec3 ref=abs(pole.z)<.92?vec3(0,0,1):vec3(1,0,0);vec3 east=normalize(cross(ref,pole));vec3 meridian=normalize(cross(pole,east));vec3 local=vec3(dot(n,east),dot(n,pole),dot(n,meridian));float c=cos(uMeridian),s=sin(uMeridian);local=vec3(c*local.x-s*local.z,local.y,s*local.x+c*local.z);
vec2 tex=vec2(atan(local.z,local.x)/6.283185307+.5,.5-asin(clamp(local.y,-1.,1.))/3.141592654);vec3 albedo=texture(uTexture,tex).rgb;
if(uKind==0){float ocean=smoothstep(.025,.12,albedo.b-max(albedo.r,albedo.g));albedo=mix(albedo,max(albedo,vec3(.02,.15,.34)),ocean*.7);}
vec3 color=pow(albedo*(.13+.87*max(sunlight,0.)),vec3(.78));if(uKind==0){float haze=pow(1.-facing,4.)*smoothstep(-.15,.28,sunlight);color+=vec3(.05,.25,.55)*haze;vec3 halfRay=normalize(normalize(uSun)-ray);float ocean=smoothstep(.025,.12,albedo.b-max(albedo.r,albedo.g));color+=vec3(1.,.91,.76)*pow(max(dot(n,halfRay),0.),100.)*max(sunlight,0.)*ocean*.4;}pixel=vec4(min(color,vec3(1.)),1.);}
gl_FragDepth=log2(1.+distance*dot(ray,uForward))/log2(1e13);return;}
if(uKind==0&&uAtmosphere>uRadius&&hit(ray,uAtmosphere,distance)){float closest=length(uCenter-ray*dot(uCenter,ray));float shell=1.-smoothstep(uRadius,uAtmosphere,closest);vec3 n=normalize(ray*distance-uCenter);float sun=smoothstep(-.12,.28,dot(n,normalize(uSun)));pixel=vec4(.18,.48,.94,shell*sun*.62);gl_FragDepth=log2(1.+distance*dot(ray,uForward))/log2(1e13);return;}discard;}
`;
const starVertex=`#version 300 es
precision highp float;layout(location=0)in vec3 p;layout(location=1)in float brightness;out float light;${common}
void main(){vec3 v=p*1e8;gl_Position=project(v);gl_Position.z=gl_Position.w*.99999;gl_PointSize=1.+brightness*1.5;light=brightness;}`;
const starFragment=`#version 300 es
precision highp float;in float light;out vec4 pixel;void main(){float r=length(gl_PointCoord-.5);pixel=vec4(.65,.78,.9,(1.-smoothstep(.05,.5,r))*light);}`;
const lineVertex=`#version 300 es
precision highp float;layout(location=0)in vec3 p;out float depth;${common}
void main(){vec3 v=p-uEye;depth=dot(v,uForward);gl_Position=project(v);gl_PointSize=7.;}`;
const lineFragment=`#version 300 es
precision highp float;in float depth;out vec4 pixel;uniform vec4 uColor;void main(){pixel=uColor;gl_FragDepth=log2(1.+max(depth,.1))/log2(1e13);}`;

function program(gl,vs,fs){const compile=(kind,text)=>{const s=gl.createShader(kind);gl.shaderSource(s,text);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s;};const p=gl.createProgram();const a=compile(gl.VERTEX_SHADER,vs),b=compile(gl.FRAGMENT_SHADER,fs);gl.attachShader(p,a);gl.attachShader(p,b);gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(p));gl.deleteShader(a);gl.deleteShader(b);return p;}
function identity(){return [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1];}
function multiply(a,b){const o=Array(16).fill(0);for(let c=0;c<4;c++)for(let r=0;r<4;r++)for(let k=0;k<4;k++)o[c*4+r]+=a[k*4+r]*b[c*4+k];return o;}
function nodeMatrix(node){if(node.matrix)return node.matrix;const q=node.rotation||[0,0,0,1],t=node.translation||[0,0,0],s=node.scale||[1,1,1];const m=identity();for(let j=0;j<3;j++){const v=rotate({x:q[0],y:q[1],z:q[2],w:q[3]},[j===0?s[j]:0,j===1?s[j]:0,j===2?s[j]:0]);m[j*4]=v[0];m[j*4+1]=v[1];m[j*4+2]=v[2];}m[12]=t[0];m[13]=t[1];m[14]=t[2];return m;}
function transform(m,p,w){return [m[0]*p[0]+m[4]*p[1]+m[8]*p[2]+m[12]*w,m[1]*p[0]+m[5]*p[1]+m[9]*p[2]+m[13]*w,m[2]*p[0]+m[6]*p[1]+m[10]*p[2]+m[14]*w];}
async function meshAsset(gl,url){const data=await(await fetch(url)).json();const bytes=Uint8Array.from(atob(data.buffers[0].uri.split(',')[1]),c=>c.charCodeAt(0));const vertices=[];
 const accessor=id=>{const a=data.accessors[id],v=data.bufferViews[a.bufferView];if(a.componentType!==5126||a.type!=='VEC3')throw Error('Built-in mesh vector format changed');const view=new DataView(bytes.buffer);return Array.from({length:a.count},(_,i)=>[0,1,2].map(j=>view.getFloat32((v.byteOffset||0)+(a.byteOffset||0)+i*(v.byteStride||12)+j*4,true)));};
 const visit=(id,parent)=>{const node=data.nodes[id],matrix=multiply(parent,nodeMatrix(node));if(node.mesh!==undefined)for(const primitive of data.meshes[node.mesh].primitives){if(primitive.mode!==4||primitive.indices!==undefined)throw Error('Built-in triangle format changed');const positions=accessor(primitive.attributes.POSITION),normals=accessor(primitive.attributes.NORMAL);const material=data.materials[primitive.material];const color=material.pbrMetallicRoughness.baseColorFactor||[.7,.7,.7,1];const x=matrix.slice(0,3),y=matrix.slice(4,7),z=matrix.slice(8,11),nx=cross(y,z),ny=cross(z,x),nz=cross(x,y);const determinant=dot(x,nx);for(let i=0;i<positions.length;i++){const p=transform(matrix,positions[i],1),n=normals[i];const normal=unit(scale(add(add(scale(nx,n[0]),scale(ny,n[1])),scale(nz,n[2])),1/determinant));vertices.push(...p,...normal,...color);}}for(const child of node.children||[])visit(child,matrix);};
 for(const id of data.scenes[data.scene||0].nodes)visit(id,identity());return makeMesh(gl,vertices);}
function makeMesh(gl,vertices){const vao=gl.createVertexArray();gl.bindVertexArray(vao);const b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(vertices),gl.STATIC_DRAW);for(const [slot,count,offset]of [[0,3,0],[1,3,12],[2,4,24]]){gl.enableVertexAttribArray(slot);gl.vertexAttribPointer(slot,count,gl.FLOAT,false,40,offset);}return {vao,count:vertices.length/10};}
async function texture(gl,url){const image=new Image();image.src=url;await image.decode();const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,image);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.REPEAT);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);return t;}

export class FlightRenderer{
 constructor(canvas,onError){this.canvas=canvas;this.mode='orbit';this.yaw=-1.05;this.pitch=.35;this.zoom=42;this.state=null;this.prediction=null;this.onError=onError;const gl=canvas.getContext('webgl2',{antialias:true,alpha:false,preserveDrawingBuffer:true});if(!gl)throw Error('This desktop preview needs WebGL 2');this.gl=gl;this.locations=new Map();this.meshProgram=program(gl,meshVertex,meshFragment);this.planetProgram=program(gl,planetVertex,planetFragment);this.starProgram=program(gl,starVertex,starFragment);this.lineProgram=program(gl,lineVertex,lineFragment);
  this.quad=gl.createVertexArray();gl.bindVertexArray(this.quad);const quad=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,quad);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);
  let seed=77191;const random=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;};const stars=[];for(let i=0;i<1100;i++){const z=random()*2-1,a=random()*Math.PI*2,r=Math.sqrt(1-z*z);stars.push(r*Math.cos(a),r*Math.sin(a),z,.2+.8*random());}this.stars=gl.createVertexArray();gl.bindVertexArray(this.stars);const starBuffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,starBuffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(stars),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,3,gl.FLOAT,false,16,0);gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,1,gl.FLOAT,false,16,12);
  this.lines=gl.createVertexArray();gl.bindVertexArray(this.lines);this.lineBuffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,this.lineBuffer);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,3,gl.FLOAT,false,0,0);
  const plume=[];for(let i=0;i<24;i++){const a=i*Math.PI/12,b=(i+1)*Math.PI/12;for(const [p,c]of [[[Math.cos(a)*.45,Math.sin(a)*.45,-4.5],[.55,.85,1,.8]],[[Math.cos(b)*.45,Math.sin(b)*.45,-4.5],[.55,.85,1,.8]],[[0,0,-12],[.07,.3,.8,0]]])plume.push(...p,0,0,1,...c);}this.plume=makeMesh(gl,plume);
  this.ready=Promise.all([meshAsset(gl,'/assets/asteria.gltf'),meshAsset(gl,'/assets/relay.gltf'),texture(gl,'/assets/earth.png'),texture(gl,'/assets/moon.jpg')]).then(([ship,station,earth,moon])=>{this.ship=ship;this.station=station;this.earth=earth;this.moon=moon;}).catch(onError);
  let drag=null;canvas.addEventListener('pointerdown',e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId);canvas.focus();});canvas.addEventListener('pointermove',e=>{if(!drag)return;this.yaw+=(e.clientX-drag[0])*.006;this.pitch=Math.max(-1.3,Math.min(1.3,this.pitch+(e.clientY-drag[1])*.006));drag=[e.clientX,e.clientY];});canvas.addEventListener('pointerup',()=>{drag=null;});canvas.addEventListener('pointercancel',()=>{drag=null;});canvas.addEventListener('wheel',e=>{e.preventDefault();this.zoom=Math.max(15,Math.min(140,this.zoom*Math.exp(e.deltaY*.001)));},{passive:false});canvas.addEventListener('webglcontextlost',e=>{e.preventDefault();onError(Error('Graphics context lost. Reload to restore the view.'));});
  const draw=()=>{try{this.draw();}catch(e){canvas.dataset.rendered='failed';onError(e);return;}requestAnimationFrame(draw);};requestAnimationFrame(draw);
 }
 uniform(p,name,value){const gl=this.gl;let locations=this.locations.get(p);if(!locations){locations={};this.locations.set(p,locations);}if(!(name in locations))locations[name]=gl.getUniformLocation(p,name);const location=locations[name];if(location===null)return;if(Array.isArray(value)||ArrayBuffer.isView(value)){if(value.length===3)gl.uniform3fv(location,value);else if(value.length===4)gl.uniform4fv(location,value);}else gl.uniform1f(location,value);}
 int(p,name,value){this.gl.uniform1i(this.gl.getUniformLocation(p,name),value);}
 centre(){this.yaw=-1.05;this.pitch=.35;this.zoom=42;}
 setMode(mode){this.mode=mode;this.centre();}
 basis(){const s=this.state.ship,r=unit(s.position),v=unit(s.velocity),n=unit(cross(r,v));let offset,origin;if(this.mode==='map'){origin=[0,0,0];const size=Math.max(s.hostRadius*1.65,Math.hypot(...s.position)*1.25);offset=scale(unit(add(add(scale(r,Math.cos(this.yaw)*Math.cos(this.pitch)),scale(v,Math.sin(this.yaw)*Math.cos(this.pitch))),scale(n,Math.sin(this.pitch)+1.3))),size*2.5*this.zoom/42);}else{origin=s.position;if(this.mode==='chase')offset=rotate(s.attitude,[0,this.zoom*.35,-this.zoom]);else offset=scale(add(add(scale(r,Math.cos(this.yaw)*Math.cos(this.pitch)),scale(v,Math.sin(this.yaw)*Math.cos(this.pitch))),scale(n,Math.sin(this.pitch))),this.zoom);}const forward=unit(scale(offset,-1)),right=unit(cross(forward,n)),up=unit(cross(right,forward));return {origin,eye:offset,right,up,forward};}
 common(p,c){this.uniform(p,'uRight',c.right);this.uniform(p,'uUp',c.up);this.uniform(p,'uForward',c.forward);this.uniform(p,'uEye',c.eye);this.uniform(p,'uAspect',this.canvas.width/this.canvas.height);this.uniform(p,'uTan',Math.tan(Math.PI/6));}
 draw(){const gl=this.gl;const ratio=Math.min(devicePixelRatio||1,2);const width=Math.round(this.canvas.clientWidth*ratio),height=Math.round(this.canvas.clientHeight*ratio);if(this.canvas.width!==width||this.canvas.height!==height){this.canvas.width=width;this.canvas.height=height;}gl.viewport(0,0,width,height);gl.clearColor(.012,.025,.044,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);if(!this.state||!this.ship)return;const c=this.basis();const sunBody=this.state.bodies.find(b=>b.id==='sun');const sun=unit(sub(sunBody?.position||[1e11,0,1e11],this.state.ship.position));gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);gl.disable(gl.DEPTH_TEST);gl.depthMask(false);gl.useProgram(this.starProgram);this.common(this.starProgram,c);gl.bindVertexArray(this.stars);gl.drawArrays(gl.POINTS,0,1100);
  gl.enable(gl.DEPTH_TEST);gl.depthFunc(gl.LEQUAL);gl.depthMask(true);gl.useProgram(this.planetProgram);this.common(this.planetProgram,c);this.uniform(this.planetProgram,'uSun',sun);gl.bindVertexArray(this.quad);for(const body of this.state.bodies){const centre=sub(sub(body.position,c.origin),c.eye);this.uniform(this.planetProgram,'uCenter',centre);this.uniform(this.planetProgram,'uRadius',body.radius);const a=body.appearance;const pole=a.orientation.north_pole_ws;this.uniform(this.planetProgram,'uPole',[pole.x,pole.y,pole.z]);this.uniform(this.planetProgram,'uMeridian',a.orientation.reference_meridian_radians);this.uniform(this.planetProgram,'uAtmosphere',a.atmosphere?.outer_radius_m||body.radius);this.int(this.planetProgram,'uKind',body.id==='sun'?2:body.id==='earth'?0:1);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,body.id==='earth'?this.earth:this.moon);this.int(this.planetProgram,'uTexture',0);gl.drawArrays(gl.TRIANGLES,0,6);}
  gl.useProgram(this.meshProgram);this.common(this.meshProgram,c);this.uniform(this.meshProgram,'uSun',sun);this.uniform(this.meshProgram,'uScale',1);this.uniform(this.meshProgram,'uEmission',0);for(const craft of this.state.crafts){if(!craft.position)continue;const q=craft.attitude;this.uniform(this.meshProgram,'uAttitude',[q.x,q.y,q.z,q.w]);this.uniform(this.meshProgram,'uPosition',sub(craft.position,c.origin));const mesh=craft.appearance==='RelayStation'?this.station:this.ship;gl.bindVertexArray(mesh.vao);gl.drawArrays(gl.TRIANGLES,0,mesh.count);if(craft.id===this.state.ship.id&&this.state.ship.massFlow>0){this.uniform(this.meshProgram,'uEmission',1.5);gl.depthMask(false);gl.blendFunc(gl.SRC_ALPHA,gl.ONE);gl.bindVertexArray(this.plume.vao);gl.drawArrays(gl.TRIANGLES,0,this.plume.count);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);gl.depthMask(true);this.uniform(this.meshProgram,'uEmission',0);}}
  if(this.mode==='map'){gl.useProgram(this.lineProgram);this.common(this.lineProgram,c);gl.bindVertexArray(this.lines);gl.bindBuffer(gl.ARRAY_BUFFER,this.lineBuffer);if(this.prediction?.generation===this.state.generation){gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(this.prediction.points.flat()),gl.DYNAMIC_DRAW);this.uniform(this.lineProgram,'uColor',[.42,.86,.81,.85]);gl.drawArrays(gl.LINE_STRIP,0,this.prediction.points.length);}gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(this.state.ship.position),gl.DYNAMIC_DRAW);this.uniform(this.lineProgram,'uColor',[1,.78,.45,1]);gl.drawArrays(gl.POINTS,0,1);}
  if(this.canvas.dataset.rendered!=='true'){const error=gl.getError();if(error!==gl.NO_ERROR)throw Error(`WebGL setup error ${error}`);this.canvas.dataset.rendered='true';}
 }
}
