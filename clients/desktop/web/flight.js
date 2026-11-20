import {FlightRenderer} from './renderer.js';
import {formatDistance as distance,apsisTiming,navigationHeading,directionGeometry,burnAttitude,renderBurnPreview,clearBurnPreview,markPreviewStale} from './planning.js';

const $=id=>document.getElementById(id);
const canvas=$('universe');
let token='',state=null,sequence=1,controlPending=null,controlBusy=false,translation=false,fine=false;
let intent={throttle:0,translation_body:{x:0,y:0,z:0},rotation_body:{x:0,y:0,z:0},attitude_mode:'Hold'};
const held=new Set(),keys=new Set();
let errorUntil=0;
let renderer,previewBusy=false;
const labels={Manual:'Manual',Hold:'Stabilise',Prograde:'Prograde',Retrograde:'Retrograde',Normal:'Normal',AntiNormal:'Anti-normal',RadialOut:'Radial out',RadialIn:'Radial in'};
const help={Manual:'Release stops control input, not existing rotation. Choose Stabilise to damp the spin.',Hold:'Stabilise damps rotation. Choose an orbital direction to keep the nose tracking it.',Prograde:'Along your motion. A short burn near periapsis mainly raises the opposite apoapsis.',Retrograde:'Against your motion. Near apoapsis, a short burn mainly lowers periapsis. Watch the lowest altitude.',Normal:'Perpendicular to the orbital plane. A burn tilts the plane; its effect depends on where you burn.',AntiNormal:'Opposite the orbit normal. A burn tilts the plane in the other direction.',RadialOut:'Away from the host centre. Changes orbital shape and orientation, rather than simply raising a circular orbit.',RadialIn:'Towards the host centre. Changes orbital shape and orientation. Check periapsis before committing.'};
const mass=value=>value>=1000?`${(value/1000).toFixed(2)} t`:`${value.toFixed(1)} kg`;
function notice(text,error=false){if(!error&&performance.now()<errorUntil)return;if(error)errorUntil=performance.now()+5000;$('notice').textContent=text;$('notice').classList.toggle('warning',error);}
function error(e){notice(e.message||String(e),true);}

async function request(path,body,keepalive=false){const options={headers:{'X-SGL-Token':token},cache:'no-store'};if(body!==undefined){options.method='POST';options.headers['Content-Type']='application/json';options.body=typeof body==='string'?body:JSON.stringify(body);options.keepalive=keepalive;}const r=await fetch(path,options);const data=await r.json();if(!r.ok)throw Error(data.error||`Flight request failed (${r.status})`);return data;}
let commandTail=Promise.resolve();
function serial(operation){const result=commandTail.then(operation);commandTail=result.catch(()=>{});return result;}
function command(action,keepalive=false,generation=state?.generation){return serial(async()=>{
 if(!generation)throw Error('The flight connection is not ready');
 if(generation!==state?.generation){if(['control','cut','heartbeat'].includes(action.action))return state;throw Error('The flight changed before that command. Try again.');}
 const result=await request('/api/command',{generation,command:action},keepalive);if(result.ship)apply(result);return result;
});}
function importDocument(text){return serial(async()=>apply(await request('/api/import',text)));}
function cloneControl(){return {throttle:intent.throttle,attitude_mode:intent.attitude_mode,translation_body:{...intent.translation_body},rotation_body:{...intent.rotation_body}};}
function enqueueControl(){if(!state)return;controlPending={control:cloneControl(),sequence:sequence++,generation:state.generation};flushControl();}
async function flushControl(){if(controlBusy)return;controlBusy=true;try{while(controlPending){const next=controlPending;controlPending=null;await command({action:'control',sequence:next.sequence,control:next.control},false,next.generation);}}catch(e){controlPending=null;error(e);}finally{controlBusy=false;}}
function clearAxes(){held.clear();keys.clear();intent.translation_body={x:0,y:0,z:0};intent.rotation_body={x:0,y:0,z:0};document.querySelectorAll('[data-axis]').forEach(b=>{b.classList.remove('held');b.dispatchEvent(new Event('sgl-input-reset'));});}
async function cut(pause=false,keepalive=false){clearAxes();intent.throttle=0;intent.attitude_mode='Manual';controlPending=null;$('throttle').value=0;showThrottle();if(!state)return;try{await command({action:'cut',sequence:sequence++},keepalive);if(pause)await command({action:'pause'},keepalive);}catch(e){if(!keepalive)error(e);}}
function axes(){const gain=fine ? 0.2 : 1;const pairs=translation?[['KeyD','KeyA',3,0],['KeyW','KeyS',1,4],['KeyQ','KeyE',2,5]]:[['KeyS','KeyW',4,1],['KeyD','KeyA',3,0],['KeyE','KeyQ',5,2]];const values=pairs.map(([positive,negative,p,n])=>gain*((keys.has(positive)||held.has(p)?1:0)-(keys.has(negative)||held.has(n)?1:0)));intent.translation_body={x:0,y:0,z:0};intent.rotation_body={x:0,y:0,z:0};const demand={x:values[0],y:values[1],z:values[2]};if(translation)intent.translation_body=demand;else{intent.rotation_body=demand;if(held.size||keys.size)intent.attitude_mode='Manual';}enqueueControl();}
function showThrottle(){$('throttleValue').replaceChildren(document.createTextNode(`${Math.round(intent.throttle*100)}`),Object.assign(document.createElement('span'),{textContent:'%'}));}
function updateDirector(ship){
 const d=directionGeometry(ship,intent.attitude_mode),marker=$('directionMarker');
 if(!d){$('alignment').textContent='Unavailable';marker.style.display='none';return;}
 marker.style.display='';marker.setAttribute('cx',String(50+d.markerX*39));marker.setAttribute('cy',String(50-d.markerY*39));marker.style.fill=d.behind?'none':'var(--teal)';
 $('directionName').textContent=labels[d.target];$('alignment').textContent=d.aligned?'Aligned':`${d.errorDegrees.toFixed(0)}° ${d.behind?'aft':'off'}`;$('spin').textContent=`Spin ${d.spinDegreesPerSecond.toFixed(2)}°/s`;$('directionSight').setAttribute('aria-label',`${labels[d.target]}, ${d.errorDegrees.toFixed(1)} degrees from nose`);
}
function updatePlanningContext(){
 if(!state)return;
 $('periapsisTiming').textContent=apsisTiming(state.ship.navigation,true)||'lowest altitude';$('apoapsisTiming').textContent=apsisTiming(state.ship.navigation,false)||'highest altitude';
 $('planNavigation').textContent=navigationHeading(state.ship.navigation);
 $('burnAttitude').textContent=burnAttitude(state.ship,$('burnAssist').value,Number($('burnThrottle').value)/100);
 const n=state.ship.navigation;
 $('guideNavigation').textContent=n?`${navigationHeading(n)}. To circularise here: ideal instantaneous Δv ${n.circularisationDeltaVMps.toFixed(1)} m/s. This includes cancelling radial motion; it is not a finite burn duration or an instruction to burn prograde.`:'Apsis timing is unavailable for this state.';
}
function apply(next){if(state&&(next.generation<state.generation||(next.generation===state.generation&&next.revision<state.revision)))return;const changed=!state||state.generation!==next.generation||state.ship.id!==next.ship.id;state=next;sequence=Math.max(sequence,next.nextControlSequence);if(changed){clearAxes();controlPending=null;intent=structuredClone(next.ship.control);if(renderer){renderer.state=next;renderer.centre();renderer.prediction=null;}clearBurnPreview($('previewResult'));}else if(!controlBusy&&!controlPending&&document.activeElement!==$('throttle'))intent=structuredClone(next.ship.control);if(renderer)renderer.state=next;const s=next.ship;$('altitude').textContent=distance(s.altitude);$('periapsis').textContent=distance(s.orbit?.periapsis??null);$('apoapsis').textContent=s.orbit&&s.orbit.apoapsis===null?'Unbound':distance(s.orbit?.apoapsis??null);$('speed').textContent=`${(s.speed/1000).toFixed(2)} km/s`;$('fuel').textContent=mass(s.fuel);$('deltaV').textContent=`${Math.round(s.deltaV).toLocaleString()} m/s`;$('flow').textContent=`${s.massFlow.toFixed(2)} kg/s`;$('acceleration').textContent=`${(next.paused?0:s.maxThrust*s.control.throttle/s.mass).toFixed(2)} m/s²`;
 if(document.activeElement!==$('throttle'))$('throttle').value=Math.round(intent.throttle*100);showThrottle();$('throttle').disabled=s.fuel<=0||s.maxThrust<=0;$('engineStatus').textContent=next.paused&&intent.throttle>0?'STAGED':s.massFlow>0?'FIRING':'IDLE';$('engineStatus').classList.toggle('burning',s.massFlow>0);$('manoeuvreHelp').textContent=help[intent.attitude_mode];$('orbitWarning').textContent=s.altitude<0?'Below the host surface. Restore a checkpoint.':s.orbit?.periapsis<0?'Surface-crossing orbit. Periapsis is below the host.':s.orbit&&s.orbit.apoapsis===null?'Unbound trajectory: no finite apoapsis.':'';
 const signature=next.crafts.map(c=>c.id+'|'+c.name).join(';');if($('craftSelect').dataset.signature!==signature){$('craftSelect').replaceChildren(...next.crafts.map(c=>Object.assign(document.createElement('option'),{value:c.id,textContent:c.name})));$('craftSelect').dataset.signature=signature;}$('craftSelect').value=s.id;
 document.querySelectorAll('[data-assist]').forEach(b=>b.classList.toggle('active',b.dataset.assist===intent.attitude_mode));document.querySelectorAll('[data-warp]').forEach(b=>{b.classList.toggle('active',Number(b.dataset.warp)===next.warp);b.disabled=Number(b.dataset.warp)>1&&(intent.throttle>0||held.size>0||keys.size>0);});$('playButton').replaceChildren(document.createTextNode(next.paused?'Run flight ':'Pause flight '),Object.assign(document.createElement('kbd'),{textContent:'Space'}));$('resetButton').disabled=!next.checkpoint;const seconds=Math.floor(next.epoch);$('epoch').textContent=`T + ${String(Math.floor(seconds/3600)).padStart(2,'0')}:${String(Math.floor(seconds/60)%60).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}`;$('flightStatus').textContent=next.paused?'Flight paused':s.massFlow>0?'Engine firing':`Coasting at ${next.warp}×`;$('sceneLabel').textContent=`${s.host.toUpperCase()} · ${s.name.toUpperCase()}`;$('visualIdentity').textContent=s.visual?`${s.visual.assetId.split(':').pop().replaceAll('-',' ')} visual`:'Visual asset unavailable';$('planButton').disabled=previewBusy||s.docking;$('previewButton').disabled=previewBusy||!next.paused||s.docking;$('integrator').disabled=previewBusy||!next.paused;$('integrator').value=next.settings.integrator;$('harmonics').textContent=`Gravity harmonics: ${next.settings.harmonics.length} authored bodies`;
 markPreviewStale($('previewResult'),next);updatePlanningContext();
 updateDirector(s);if(next.notice)notice(next.notice);else if(!controlBusy)notice(next.paused&&intent.throttle>0?'Throttle is staged. Run starts the burn; CUT clears it.':'Mark a checkpoint, align the nose, then ease up the throttle.');}

async function save(){const r=await fetch('/api/save',{headers:{'X-SGL-Token':token}});if(!r.ok)throw Error('The native save could not be exported');const blob=await r.blob(),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`sgl-flight-${new Date().toISOString().replaceAll(':','-')}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
async function replaceWorld(operation){await cut(true);await save();await operation();notice('Previous flight downloaded. The new world is ready.');}
async function prediction(){if(!renderer)return;try{const result=await command({action:'prediction'});if(result.generation===state.generation){renderer.prediction=result;$('predictionLabel').textContent=`Engine-off coast · native t = ${result.epoch.toFixed(1)} s`;}}catch(e){error(e);}}
function camera(mode){if(!renderer)return;renderer.setMode(mode);document.querySelectorAll('[data-camera]').forEach(b=>b.classList.toggle('active',b.dataset.camera===mode));$('mapCaption').hidden=mode!=='map';if(mode==='map')prediction();}
function focusScene(event){if(event?.detail>0&&!$('planner').open&&!$('guide').open)canvas.focus({preventScroll:true});}
document.querySelectorAll('button').forEach(button=>button.addEventListener('click',focusScene));
document.querySelectorAll('[data-camera]').forEach(b=>b.addEventListener('click',()=>camera(b.dataset.camera)));
$('centreButton').onclick=()=>renderer?.centre();$('predictionButton').onclick=prediction;
$('throttle').oninput=()=>{intent.throttle=Number($('throttle').value)/100;showThrottle();enqueueControl();};
$('cutButton').onclick=()=>cut();$('playButton').onclick=()=>command({action:state?.paused?'resume':'pause'}).catch(error);
document.querySelectorAll('[data-warp]').forEach(b=>b.onclick=()=>command({action:'warp',rate:Number(b.dataset.warp)}).catch(error));
document.querySelectorAll('[data-assist]').forEach(b=>b.onclick=()=>{clearAxes();intent.attitude_mode=b.dataset.assist;enqueueControl();});
document.querySelectorAll('[data-axis]').forEach(b=>{
 const index=Number(b.dataset.axis),pointers=new Set();let pulse=null;
 const sync=()=>{const active=pointers.size>0||pulse!==null;if(active)held.add(index);else held.delete(index);b.classList.toggle('held',active);axes();};
 const cancelPulse=()=>{if(pulse!==null)clearTimeout(pulse);pulse=null;};
 b.addEventListener('sgl-input-reset',()=>{cancelPulse();pointers.clear();});
 b.addEventListener('pointerdown',e=>{e.preventDefault();cancelPulse();pointers.add(e.pointerId);b.setPointerCapture(e.pointerId);sync();});
 const release=e=>{if(pointers.delete(e.pointerId))sync();};
 b.addEventListener('pointerup',release);b.addEventListener('pointercancel',release);b.addEventListener('lostpointercapture',release);
 b.addEventListener('click',e=>{if(e.detail===0&&pointers.size===0){cancelPulse();pulse=setTimeout(()=>{pulse=null;sync();},180);sync();}});
});
$('translationButton').onclick=async()=>{await cut();translation=!translation;$('translationButton').textContent=translation?'RCS':'Rotate';$('translationButton').classList.toggle('active',translation);const names=translation?['Left','Up','Forward','Right','Down','Back']:['Yaw left','Pitch up','Roll left','Yaw right','Pitch down','Roll right'];document.querySelectorAll('[data-axis]').forEach(b=>b.textContent=names[Number(b.dataset.axis)]);};
$('fineButton').onclick=()=>{fine=!fine;$('fineButton').textContent=fine?'Fine · 20%':'Full control';$('fineButton').classList.toggle('active',fine);if(held.size||keys.size)axes();};
$('craftSelect').onchange=()=>command({action:'select',body_id:$('craftSelect').value}).catch(error);
$('markButton').onclick=()=>command({action:'checkpoint'}).catch(error);$('resetButton').onclick=()=>command({action:'reset'}).catch(error);
$('saveButton').onclick=()=>save().then(()=>notice('Portable flight saved.')).catch(error);
$('workshopButton').onclick=()=>replaceWorld(()=>command({action:'workshop'})).catch(error);
$('importFile').onchange=async()=>{const file=$('importFile').files[0];$('importFile').value='';if(!file)return;try{if(file.size>2*1024*1024)throw Error('Choose an SGL document smaller than 2 MiB');const text=await file.text();const format=JSON.parse(text).format;if(format==='solarlab.spacecraft'){await command({action:'pause'});await importDocument(text);}else await replaceWorld(()=>importDocument(text));}catch(e){error(e);}};
$('guideButton').onclick=async()=>{try{await command({action:'pause'});$('guide').showModal();}catch(e){error(e);}};
for(const [value,label]of Object.entries(labels))$('burnAssist').append(Object.assign(document.createElement('option'),{value,textContent:label}));
$('planButton').onclick=async()=>{if(previewBusy)return;try{await command({action:'pause'});$('burnAssist').value=intent.attitude_mode;$('burnThrottle').value=Math.round((intent.throttle||.25)*100);clearBurnPreview($('previewResult'));updatePlanningContext();$('planner').showModal();$('planner').scrollTop=0;}catch(e){error(e);}};
$('closePlanner').onclick=()=>$('planner').close();
for(const id of ['burnSeconds','burnThrottle','burnAssist'])$(id).addEventListener('input',()=>{clearBurnPreview($('previewResult'));updatePlanningContext();});
$('integrator').onchange=()=>command({action:'integrator',method:$('integrator').value}).catch(error);
$('previewButton').onclick=async()=>{
 if(previewBusy)return;
 const result=$('previewResult');result.textContent='Calculating with the native flight solver…';delete result.dataset.revision;
 previewBusy=true;$('planButton').disabled=true;$('previewButton').disabled=true;for(const id of ['burnSeconds','burnThrottle','burnAssist','integrator'])$(id).disabled=true;
 try{
  const response=await command({action:'preview',duration:Number($('burnSeconds').value),throttle:Number($('burnThrottle').value)/100,assist:$('burnAssist').value});
  const p=response.preview;if(response.generation!==state.generation)throw Error('Flight changed. Calculate again.');
  renderBurnPreview(result,p,response.generation);markPreviewStale(result,state);
 }catch(e){result.textContent=e.message;}
 finally{previewBusy=false;$('planButton').disabled=Boolean(state?.ship.docking);$('previewButton').disabled=!state?.paused||Boolean(state?.ship.docking);for(const id of ['burnSeconds','burnThrottle','burnAssist'])$(id).disabled=false;$('integrator').disabled=!state?.paused;}
};

window.addEventListener('keydown',e=>{if(['INPUT','SELECT','TEXTAREA'].includes(document.activeElement?.tagName)||$('guide').open||$('planner').open)return;if(['KeyW','KeyA','KeyS','KeyD','KeyQ','KeyE'].includes(e.code)){e.preventDefault();if(!e.repeat&&!keys.has(e.code)){keys.add(e.code);axes();}return;}if(e.repeat)return;if(e.code==='KeyX'){e.preventDefault();cut();}else if(e.code==='Space'&&document.activeElement?.tagName!=='BUTTON'){e.preventDefault();command({action:state?.paused?'resume':'pause'}).catch(error);}else if(['Equal','NumpadAdd','Minus','NumpadSubtract'].includes(e.code)){intent.throttle=Math.max(0,Math.min(1,intent.throttle+((e.code==='Equal'||e.code==='NumpadAdd') ? 0.05 : -0.05)));enqueueControl();showThrottle();}else if(e.code==='KeyR')renderer?.centre();else if(e.code==='KeyC'){const modes=['orbit','chase','map'];camera(modes[(modes.indexOf(renderer?.mode)+1)%3]);}else if(e.key==='?')$('guideButton').click();});
window.addEventListener('keyup',e=>{if(keys.delete(e.code))axes();});
window.addEventListener('blur',()=>{if(state)cut(true,true);});window.addEventListener('pagehide',()=>{if(state)cut(true,true);});document.addEventListener('visibilitychange',()=>{if(document.hidden&&state)cut(true,true);});

async function start(){try{renderer=new FlightRenderer(canvas,error);await renderer.ready;const session=await request('/api/session');token=session.token;sequence=session.nextControlSequence;apply(await request('/api/state'));const poll=async()=>{try{apply(await request('/api/state'));}catch(e){error(e);}setTimeout(poll,100);};poll();setInterval(()=>{if(state&&document.visibilityState==='visible'&&document.hasFocus())command({action:'heartbeat'}).catch(()=>{});},450);canvas.focus();}catch(e){error(e);}}
start();
