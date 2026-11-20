// Presentation of native telemetry only. No force model or trajectory propagation.
export const formatDistance=value=>value===null||!Number.isFinite(value)?'—':Math.abs(value)>=1e6?`${(value/1000).toLocaleString('en-US',{maximumFractionDigits:0})} km`:Math.abs(value)>=1000?`${(value/1000).toFixed(1)} km`:`${value.toFixed(0)} m`;

export function orbitShape(hostRadius,beforePeriapsis,beforeApoapsis,afterPeriapsis,afterApoapsis){
 if(!Number.isFinite(hostRadius)||hostRadius<0)return null;
 const radii=(p,a)=>{if(!Number.isFinite(p)||!Number.isFinite(a))return null;const peri=hostRadius+p,apo=hostRadius+a;return Number.isFinite(peri)&&Number.isFinite(apo)&&peri>0&&apo>=peri?{peri,apo}:null;};
 const before=radii(beforePeriapsis,beforeApoapsis),after=radii(afterPeriapsis,afterApoapsis);
 if(!before&&!after)return null;
 const unit=Math.max(hostRadius,before?.apo||0,after?.apo||0),host=hostRadius/unit;
 const ellipse=r=>{if(!r)return null;const peri=r.peri/unit,apo=r.apo/unit;return {centreX:(peri-apo)/2,major:(peri+apo)/2,minor:Math.sqrt(peri*apo)};};
 const a=ellipse(before),b=ellipse(after);
 return {hostRadius:host,before:a,after:b,left:Math.max(host,(before?.apo||0)/unit,(after?.apo||0)/unit),right:Math.max(host,(before?.peri||0)/unit,(after?.peri||0)/unit),halfHeight:Math.max(host,a?.minor||0,b?.minor||0)};
}

export function apsisChange(label,before,after){
 if(!Number.isFinite(after))return `${label} has no finite estimate after the burn`;
 if(!Number.isFinite(before))return `${label} becomes ${formatDistance(after)}`;
 const change=after-before;
 if(!Number.isFinite(change))return `${label} changes beyond the display range`;
 return Math.abs(change)<1?`${label} changes by less than 1 m`:`${label} ${change>0?'rises':'falls'} ${formatDistance(Math.abs(change))}`;
}

export function countdown(seconds){
 if(!Number.isFinite(seconds)||seconds<0)return null;
 if(seconds===0)return 'now';
 if(seconds<1)return 'in <1 s';
 if(seconds>=86400)return `in ${(seconds/86400).toFixed(1)} d`;
 const whole=Math.ceil(seconds);
 return whole>=3600?`in ${Math.floor(whole/3600)} h ${Math.floor(whole%3600/60)} m`:whole>=60?`in ${Math.floor(whole/60)} m ${whole%60} s`:`in ${whole} s`;
}

export function apsisTiming(guidance,periapsis){
 if(!guidance)return null;
 const time=countdown(periapsis?guidance.timeToPeriapsisSeconds:guidance.timeToApoapsisSeconds);
 if(time)return `≈ ${time}`;
 return guidance.regime==='nearCircular'?'no distinct apsis':guidance.regime==='nearParabolic'?'timing unavailable':guidance.regime==='hyperbolic'?(periapsis?'no future estimate':'no apoapsis'):null;
}

export function navigationHeading(guidance){
 if(!guidance)return 'Apsis timing unavailable';
 const valid=value=>Number.isFinite(value)&&value>=0;
 const p=guidance.timeToPeriapsisSeconds,a=guidance.timeToApoapsisSeconds;
 if(valid(p)&&(!valid(a)||p<=a))return `Periapsis ${countdown(p)}`;
 if(valid(a))return `Apoapsis ${countdown(a)}`;
 return guidance.regime==='nearCircular'?'Nearly circular · no distinct apsis':guidance.regime==='hyperbolic'?'Open trajectory · no future periapsis estimate':guidance.regime==='nearParabolic'?'Apsis timing unavailable near escape speed':'Apsis timing unavailable';
}

const normal=v=>{const n=Array.isArray(v)&&v.length===3&&v.every(Number.isFinite)?Math.hypot(...v):0;return n>1e-12&&Number.isFinite(n)?v.map(x=>x/n):null;};
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
export function directionGeometry(ship,assist){
 if(!ship)return null;
 const target=['Manual','Hold'].includes(assist)?'Prograde':assist;
 const position=normal(ship.position),velocity=normal(ship.velocity);
 let direction=['RadialOut','RadialIn'].includes(target)?position:['Normal','AntiNormal'].includes(target)?position&&velocity&&normal(cross(position,velocity)):velocity;
 if(!direction)return null;
 if(['Retrograde','AntiNormal','RadialIn'].includes(target))direction=direction.map(v=>-v);
 const attitude=ship.attitude;if(!attitude)return null;
 const norm=Math.hypot(attitude.w,attitude.x,attitude.y,attitude.z);if(!Number.isFinite(norm)||norm<1e-12)return null;
 const q=[-attitude.x/norm,-attitude.y/norm,-attitude.z/norm],w=attitude.w/norm,t=cross(q,direction),u=cross(q,t);
 const body=normal(direction.map((value,i)=>value+2*(w*t[i]+u[i])));if(!body)return null;
 const angle=Math.acos(Math.max(-1,Math.min(1,body[2]))),radius=Math.min(1,angle/(Math.PI/2)),transverse=Math.hypot(body[0],body[1]);
 return {target,errorDegrees:angle*180/Math.PI,aligned:angle<=Math.PI/60,behind:body[2]<0,markerX:transverse>1e-10?body[0]/transverse*radius:0,markerY:transverse>1e-10?body[1]/transverse*radius:body[2]<0?1:0,spinDegreesPerSecond:Math.hypot(...ship.spin)*180/Math.PI};
}

export function burnAttitude(ship,assist,throttle){
 if(!ship)return 'Waiting for the spacecraft state';
 if(throttle<=0)return 'Coast preview · main engine off';
 if(ship.maxThrust<=0)return 'This spacecraft has no main engine';
 if(ship.fuel<=0)return 'No propellant remains for this burn';
 if(assist==='Manual')return 'Current nose direction · existing spin continues';
 if(assist==='Hold')return 'Current nose direction · Stabilise damps spin';
 const d=directionGeometry(ship,assist);if(!d)return 'The selected direction is unavailable at this state';
 if(!d.aligned)return `${d.errorDegrees.toFixed(0)}° to turn · thrust starts before alignment`;
 if(d.spinDegreesPerSecond>.5)return `Nose aligned · still rotating ${d.spinDegreesPerSecond.toFixed(1)}°/s`;
 return `Nose aligned with ${assist.replace(/([a-z])([A-Z])/g,'$1 $2').toLowerCase()}`;
}

function shapeFigure(shape,document){
 const figure=document.createElement('figure');figure.className='orbit-shape';
 const heading=document.createElement('div');heading.className='orbit-shape-legend';heading.textContent='ORBIT SIZE · before dashed / after solid';figure.append(heading);
 const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 540 148');svg.setAttribute('role','img');svg.setAttribute('aria-label','Orbit size comparison. Before is dashed, after is solid. Same scale, with both periapses drawn to the right. Orbital plane and rotation are not shown.');
 const scale=Math.min(520/(shape.left+shape.right),128/(2*shape.halfHeight)),focus=(540+(shape.left-shape.right)*scale)/2;
 function element(tag,attributes){const node=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const [key,value]of Object.entries(attributes))node.setAttribute(key,String(value));svg.append(node);}
 element('circle',{class:'orbit-shape-host',cx:focus,cy:74,r:shape.hostRadius*scale});
 for(const [key,orbit]of [['before',shape.before],['after',shape.after]]){if(!orbit)continue;const cx=focus+orbit.centreX*scale;const rx=orbit.major*scale; element('ellipse',{class:`orbit-shape-${key}`,cx,cy:74,rx,ry:orbit.minor*scale});if(key==='after')for(const sign of [-1,1])element('circle',{class:'orbit-shape-apsis',cx:cx+sign*rx,cy:74,r:2.5});}
 figure.append(svg);const caption=document.createElement('figcaption');caption.textContent='Same scale; periapses drawn to the right. Plane and rotation are not shown.';figure.append(caption);
 if(!shape.before||!shape.after){const missing=document.createElement('small');missing.textContent=`${shape.after?'Before':'After'}: no closed ellipse to display.`;figure.append(missing);}
 return figure;
}

export function renderBurnPreview(result,preview,generation){
 const document=result.ownerDocument,p=preview;
 result.replaceChildren();result.classList.remove('preview-stale');result.dataset.revision=String(p.revision);result.dataset.epoch=String(p.epochSeconds);result.dataset.generation=String(generation);
 const heading=document.createElement('h2');heading.className='preview-heading';heading.textContent='Current orbit → after burn';result.append(heading);
 const grid=document.createElement('div');grid.className='result-grid';
 for(const [label,value]of [['Periapsis',`${formatDistance(p.beforePeriapsisM)} → ${formatDistance(p.afterPeriapsisM)}`],['Apoapsis',`${formatDistance(p.beforeApoapsisM)} → ${formatDistance(p.afterApoapsisM)}`],['Fuel used',`${p.fuelUsedKg.toFixed(2)} kg`],['Elapsed',`${p.elapsedSeconds.toFixed(1)} seconds`]]){const entry=document.createElement('div');entry.textContent=label;const number=document.createElement('strong');number.textContent=value;entry.append(number);grid.append(entry);}
 result.append(grid);const effect=document.createElement('p');effect.className='burn-effect';effect.textContent=`${apsisChange('Periapsis',p.beforePeriapsisM,p.afterPeriapsisM)}. ${apsisChange('Apoapsis',p.beforeApoapsisM,p.afterApoapsisM)}.`;result.append(effect);
 const shape=orbitShape(p.referenceRadiusM,p.beforePeriapsisM,p.beforeApoapsisM,p.afterPeriapsisM,p.afterApoapsisM);if(shape)result.append(shapeFigure(shape,document));
 if(p.afterGuidance){const next=document.createElement('p');next.className='after-navigation';next.textContent=`After burn: ${navigationHeading(p.afterGuidance)}`;result.append(next);}
 const list=document.createElement('ul');for(const warning of p.warnings){const item=document.createElement('li');item.textContent=warning;list.append(item);}result.append(list);
 const receipt=document.createElement('small');receipt.textContent=`Native source t = ${p.epochSeconds.toFixed(1)} s · revision ${p.revision}. Live flight is unchanged.`;result.append(receipt);
}

export function markPreviewStale(result,state){
 const stale=Boolean(result.dataset.revision)&&(Number(result.dataset.revision)!==state.revision||Number(result.dataset.epoch)!==state.epoch||Number(result.dataset.generation)!==state.generation);
 result.classList.toggle('preview-stale',stale);
 if(stale){const heading=result.querySelector('.preview-heading');if(heading)heading.textContent='Flight changed. Calculate again.';}
 return stale;
}
