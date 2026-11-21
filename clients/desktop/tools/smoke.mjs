// Run after cargo build -p solarlab-desktop. Actual native bridge and WebGL;
// no mocked world or JavaScript propagation. Needs playwright-core + Chromium.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,rename,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.SGL_PLAYWRIGHT_MODULE||'playwright-core');
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..');
const output=path.resolve(process.env.SGL_DESKTOP_EVIDENCE||path.join(root,'out/desktop-proof'));
await mkdir(output,{recursive:true});
const binary=process.env.SGL_DESKTOP_BINARY||path.join(root,'target/debug/solarlab-desktop');
const port=Number(process.env.SGL_DESKTOP_PORT||8791),base=`http://127.0.0.1:${port}`;
const server=spawn(binary,['--port',String(port)],{cwd:root,stdio:['ignore','pipe','pipe']});
let serverOutput='';server.stdout.on('data',b=>serverOutput+=b);server.stderr.on('data',b=>serverOutput+=b);
let browser,token;
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(test,description,timeout=15000){const start=Date.now();while(Date.now()-start<timeout){if(await test())return;await wait(100);}throw Error(`Timed out: ${description}`);}
async function native(){const r=await fetch(base+'/api/state',{headers:{'X-SGL-Token':token}});assert.equal(r.status,200);return r.json();}
const errors=[],httpErrors=[],captures={},poweredCaptures=[],plannerViews={};
async function capture(page,name,expectedCamera){
 // Node timers do not advance the browser's presentation clock under software
 // WebGL. Finish finite UI transitions and observe two browser frames before
 // acquiring genuine pixels; do not change camera classes or product styles.
 await page.evaluate(async()=>{
  for(const animation of document.getAnimations()){
   if(Number.isFinite(animation.effect?.getComputedTiming().endTime))animation.finish();
  }
  await new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>reject(Error('Screenshot presentation frames did not complete')),15000);
   requestAnimationFrame(()=>requestAnimationFrame(()=>{clearTimeout(timer);resolve();}));
  });
 });
 const cameras=await page.locator('[data-camera]').evaluateAll(buttons=>buttons.map(button=>{
  const style=getComputedStyle(button);
  return {mode:button.dataset.camera,active:button.classList.contains('active'),background:style.backgroundColor,border:style.borderTopColor};
 }));
 captures[name]={expectedCamera,cameras};
 assert.deepEqual(cameras.filter(button=>button.active).map(button=>button.mode),[expectedCamera],'Exactly the requested camera must be active');
 assert.deepEqual(cameras.filter(button=>button.background!=='rgba(0, 0, 0, 0)').map(button=>button.mode),[expectedCamera],'Only the active camera may retain its presented background');
 assert.deepEqual(cameras.filter(button=>button.border!=='rgba(0, 0, 0, 0)').map(button=>button.mode),[expectedCamera],'Only the active camera may retain its presented border');
 await page.screenshot({path:path.join(output,name),animations:'disabled',timeout:15000});
}
async function visibleControls(page){
 const selectors=['#playButton','#cutButton','#throttle','#craftSelect','#centreButton','#fineButton','[data-axis="4"]'];
 const result=await page.evaluate(selectors=>Object.fromEntries(selectors.map(selector=>{const e=document.querySelector(selector),r=e.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2,top=document.elementFromPoint(x,y);return [selector,r.width>0&&r.height>0&&r.x>=0&&r.y>=0&&r.right<=innerWidth&&r.bottom<=innerHeight&&(top===e||e.contains(top))];})),selectors);
 assert.ok(Object.values(result).every(Boolean),`Core controls must be visible and reachable: ${JSON.stringify(result)}`);return result;
}
async function visiblePlanner(page){
 const selectors=['#burnSeconds','#burnThrottle','#burnAssist','#previewButton','#planNavigation','#burnAttitude','#previewResult .preview-heading','#previewResult .result-grid','#previewResult .orbit-shape svg','#previewResult .orbit-shape figcaption'];
 const result=await page.evaluate(selectors=>{
  const dialog=document.querySelector('#planner').getBoundingClientRect();
  const elements=Object.fromEntries(selectors.map(selector=>{
   const element=document.querySelector(selector),bounds=element.getBoundingClientRect(),hit=document.elementFromPoint(bounds.x+bounds.width/2,bounds.y+bounds.height/2);
   const visible=bounds.width>0&&bounds.height>0&&bounds.left>=Math.max(0,dialog.left)&&bounds.top>=Math.max(0,dialog.top)&&bounds.right<=Math.min(innerWidth,dialog.right)&&bounds.bottom<=Math.min(innerHeight,dialog.bottom)&&(hit===element||element.contains(hit));
   return [selector,{visible,bounds:{x:bounds.x,y:bounds.y,width:bounds.width,height:bounds.height}}];
  }));
  return {viewport:{width:innerWidth,height:innerHeight},dialog:{x:dialog.x,y:dialog.y,width:dialog.width,height:dialog.height},elements};
 },selectors);
 assert.ok(Object.values(result.elements).every(value=>value.visible),`Burn controls, full orbit diagram and projection caption must be simultaneously visible without scrolling: ${JSON.stringify(result)}`);
 return result;
}
async function scenePixels(page){
 return page.evaluate(()=>{const c=document.querySelector('#universe'),gl=c.getContext('webgl2'),bytes=new Uint8Array(c.width*c.height*4);gl.readPixels(0,0,c.width,c.height,gl.RGBA,gl.UNSIGNED_BYTE,bytes);let colored=0;const shades=new Set();for(let i=0;i<bytes.length;i+=16){const r=bytes[i],g=bytes[i+1],b=bytes[i+2];if(Math.max(r,g,b)>40)colored++;shades.add(`${r>>4},${g>>4},${b>>4}`);}return {coloredSamples:colored,shades:shades.size,meshKind:c.dataset.meshKind,assetId:c.dataset.assetId,solidVertices:Number(c.dataset.solidVertices),exhaustVertices:Number(c.dataset.exhaustVertices)};});
}
async function capturePoweredFlight(page){
 const powered=s=>!s.paused&&s.ship.massFlow>0&&s.ship.control.throttle>0;
 for(let attempt=1;attempt<=3;attempt++){
  const before=await native(),began=Date.now();
  assert.ok(powered(before),'A powered screenshot must start from an actual live native burn');
  await page.waitForFunction(({epoch,generation})=>{
   const canvas=document.querySelector('#universe');
   return Number(canvas.dataset.flightGeneration)===generation&&Number(canvas.dataset.flightEpoch)>=epoch&&Number(canvas.dataset.exhaustVertices)>0&&document.querySelector('#engineStatus').textContent==='FIRING';
  },{epoch:before.epoch,generation:before.generation},{timeout:15000});
  // The plume assertion reads the same submitted vertex count as before.
  // Avoid a redundant full-frame software GPU readback during a live burn.
  assert.ok(await page.locator('#universe').evaluate(c=>Number(c.dataset.exhaustVertices)>0),'Powered native spacecraft must draw its authored exhaust');
  await capture(page,'desktop-powered-flight.png','orbit');
  const after=await native();
  const accepted=powered(after)&&after.generation===before.generation;
  const evidence={attempt,accepted,beforeGeneration:before.generation,afterGeneration:after.generation,beforeEpoch:before.epoch,afterEpoch:after.epoch,afterPaused:after.paused,afterMassFlow:after.ship.massFlow,elapsedMs:Date.now()-began,notice:after.notice};
  poweredCaptures.push(evidence);
  if(accepted)return;
  const rejectedName=`desktop-powered-flight-watchdog-${attempt}.png`;
  await rename(path.join(output,'desktop-powered-flight.png'),path.join(output,rejectedName));
  captures[rejectedName]=captures['desktop-powered-flight.png'];delete captures['desktop-powered-flight.png'];
  assert.ok(after.paused&&after.ship.control.throttle===0&&after.generation>before.generation&&after.notice?.includes('lost contact'),`Unexpected native change during powered capture: ${JSON.stringify(evidence)}`);
  assert.ok(attempt<3,`Software capture repeatedly triggered the native watchdog: ${JSON.stringify(poweredCaptures)}`);
  // Keep the 1.5-second watchdog. A slow software capture may trigger it;
  // preserve that image and report, then recover through the same real UI.
  await page.waitForFunction(generation=>Number(document.querySelector('#universe').dataset.flightGeneration)===generation&&document.querySelector('#playButton').textContent.includes('Run flight'),after.generation,{timeout:15000});
  await page.click('[data-assist="Prograde"]');await until(async()=>(await native()).ship.control.attitude_mode==='Prograde','watchdog recovery alignment input');
  const slider=await page.locator('#throttle').boundingBox();await page.mouse.click(slider.x+slider.width*.4,slider.y+slider.height*.5);
  await until(async()=>(await native()).ship.control.throttle>.3,'watchdog recovery throttle input');await page.click('#playButton');
  await until(async()=>{const current=await native();return powered(current)&&current.ship.fuel<after.ship.fuel-.5;},'watchdog recovery native burn');
 }
}
try{
 await until(async()=>{try{return (await fetch(base)).ok;}catch{return false;}},'native host startup');
 token=(await(await fetch(base+'/api/session')).json()).token;
 browser=await chromium.launch({headless:true,executablePath:process.env.SGL_CHROMIUM||'/usr/bin/chromium',args:['--no-sandbox','--disable-dev-shm-usage','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const context=await browser.newContext({viewport:{width:1440,height:960},deviceScaleFactor:1,acceptDownloads:true});
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&!m.text().includes('favicon'))errors.push(m.text());});
 page.on('response',async r=>{if(r.status()>=400){let action;try{action=r.request().postDataJSON()?.command?.action;}catch{}httpErrors.push({url:new URL(r.url()).pathname,status:r.status(),action,body:(await r.text().catch(()=>'' )).slice(0,600)});}});
 await page.goto(base,{waitUntil:'domcontentloaded'});await page.bringToFront();
 await page.waitForFunction(()=>document.querySelector('#altitude').textContent!=='—');
 await page.waitForFunction(()=>document.querySelector('#universe').dataset.rendered==='true');
 await wait(400);const controlsLarge=await visibleControls(page);const initialPixels=await scenePixels(page);assert.ok(initialPixels.coloredSamples>1000&&initialPixels.shades>24,`Visible spacecraft and planetary scene required: ${JSON.stringify(initialPixels)}`);assert.equal(initialPixels.exhaustVertices,0,'Paused idle spacecraft must not draw exhaust');await capture(page,'desktop-orbit.png','orbit');
 await page.locator('#universe').click({position:{x:720,y:450}});
 await page.keyboard.down('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===-1,'keyboard pitch reaches native control');
 await page.keyboard.down('KeyS');await until(async()=>(await native()).ship.control.rotation_body.x===0,'opposing held keys cancel');
 await page.keyboard.up('KeyS');await until(async()=>(await native()).ship.control.rotation_body.x===-1,'one key release preserves other input');
 await page.keyboard.up('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===0,'keyboard release clears pitch');
 await page.click('#fineButton');await page.keyboard.down('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===-.2,'fine keyboard authority');await page.keyboard.up('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===0,'fine input release');await page.click('#fineButton');
 // CUT clears local held-key state. OS key-repeat must not re-arm that
 // old press, even after a new native generation is adopted on reconnect.
 await page.keyboard.down('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===-1,'held pitch before CUT');
 await page.click('#cutButton');await until(async()=>(await native()).ship.control.rotation_body.x===0,'CUT clears held pitch');
 const releasedSequence=(await native()).nextControlSequence;
 await page.keyboard.down('KeyW');await page.keyboard.up('KeyW');
 await page.click('#guideButton');await page.waitForFunction(()=>document.querySelector('#guide').open);
 assert.equal((await native()).nextControlSequence,releasedSequence,'Key-repeat after CUT must not submit old held input');
 await page.click('#guide .close-guide');
 // Deliberately delayed replies also delay serialized browser heartbeats.
 // Start from real idle controls: paused Stabilise is still an armed control
 // and the unchanged native watchdog correctly clears it after lost contact.
 await page.click('[data-assist="Manual"]');await until(async()=>(await native()).ship.control.attitude_mode==='Manual','neutral manual controls');
 const initial=await native();assert.equal(initial.paused,true);assert.equal(initial.ship.altitude,400000);
 assert.equal(initial.ship.control.throttle,0);
 assert.deepEqual(initial.ship.control.translation_body,{x:0,y:0,z:0});
 assert.deepEqual(initial.ship.control.rotation_body,{x:0,y:0,z:0});
 // A double-click must acquire one opening before the real pause reply arrives.
 // Keep counting pause requests until the preview reaches native code so a
 // second opener hidden in the serial command queue cannot escape this check.
 let heldOpening=false,openingStatePolls=0,openingPauseRequests=0,releaseOpening;
 const openingReleased=new Promise(resolve=>{releaseOpening=resolve;});
 const countOpeningPolls=response=>{if(heldOpening&&new URL(response.url()).pathname==='/api/state')openingStatePolls++;};
 const countOpeningPauses=request=>{if(new URL(request.url()).pathname==='/api/command'&&request.postDataJSON()?.command?.action==='pause')openingPauseRequests++;};
 const holdNativeOpening=async route=>{if(route.request().postDataJSON()?.command?.action!=='pause'){await route.continue();return;}const response=await route.fetch();heldOpening=true;await openingReleased;await route.fulfill({response});};
 page.on('response',countOpeningPolls);page.on('request',countOpeningPauses);
 await page.route('**/api/command',holdNativeOpening);
 try{
  await page.dblclick('#planButton');await until(()=>heldOpening&&openingStatePolls>=2,'state polls during native planner opening');
  assert.equal(await page.locator('#planButton').isDisabled(),true,'Opening must remain acquired across native pause delay and state polling');
  assert.equal(await page.locator('#planner').evaluate(dialog=>dialog.open),false,'The planner must wait for its native pause response');
 }finally{releaseOpening();page.off('response',countOpeningPolls);}
 await page.waitForFunction(()=>document.querySelector('#planner').open);
 await page.unroute('**/api/command',holdNativeOpening);
 await page.selectOption('#burnAssist','Prograde');await page.fill('#burnSeconds','30');await page.fill('#burnThrottle','40');
 assert.ok((await page.locator('#planNavigation').innerText()).length>0);assert.ok((await page.locator('#burnAttitude').innerText()).includes('aligned'));
 // Hold only the actual native response. State polls remain real and active;
 // the test never substitutes a simulated burn result.
 let heldPreview=false,pendingStatePolls=0,releasePreview;
 const previewReleased=new Promise(resolve=>{releasePreview=resolve;});
 const countPendingPolls=response=>{if(heldPreview&&new URL(response.url()).pathname==='/api/state')pendingStatePolls++;};
 page.on('response',countPendingPolls);
 const holdNativePreview=async route=>{if(route.request().postDataJSON()?.command?.action!=='preview'){await route.continue();return;}const response=await route.fetch();heldPreview=true;await previewReleased;await route.fulfill({response});};
 await page.route('**/api/command',holdNativePreview);
 try{
  await page.click('#previewButton');await until(()=>heldPreview&&pendingStatePolls>=2,'state polls during native burn preview');
  assert.equal(openingPauseRequests,1,'A double-click must issue only one planner-opening pause, with no queued form reset');
  page.off('request',countOpeningPauses);
  for(const selector of ['#planButton','#previewButton','#burnSeconds','#burnThrottle','#burnAssist','#integrator'])assert.equal(await page.locator(selector).isDisabled(),true,`${selector} must stay disabled through pending native response and state polling`);
  await page.click('#closePlanner');
  const pollsAtClose=pendingStatePolls;
  await page.locator('#planButton').evaluate(button=>button.click());
  await until(()=>pendingStatePolls>=pollsAtClose+2,'state polls after closing a pending preview');
  assert.equal(await page.locator('#planButton').isDisabled(),true,'A pending calculation must prevent reopening and resetting its form');
  assert.equal(await page.locator('#planner').evaluate(dialog=>dialog.open),false,'Reopening must wait for the original response to finish');
  assert.equal(await page.locator('#burnAssist').inputValue(),'Prograde');
  assert.equal(await page.locator('#burnThrottle').inputValue(),'40');
 }finally{releasePreview();page.off('response',countPendingPolls);}
 await page.waitForFunction(()=>document.querySelector('#previewResult').textContent.includes('Native source'));
 await page.unroute('**/api/command',holdNativePreview);
 assert.equal(await page.locator('#planner').evaluate(dialog=>dialog.open),false,'Completing a closed preview must not reopen it');
 await page.click('#planButton');
 await page.waitForFunction(()=>document.querySelector('#planner').open);
 assert.equal(await page.locator('#burnAssist').inputValue(),'Manual');
 assert.equal(await page.locator('#burnThrottle').inputValue(),'25');
 assert.equal(await page.locator('#previewResult .orbit-shape').count(),0);
 assert.equal(await page.locator('#previewResult').getAttribute('data-revision'),null,'Reopened form must clear the previous request identity');
 assert.equal((await page.locator('#previewResult').innerText()).includes('Native source'),false,'A previous response must not describe the reopened form');
 await page.selectOption('#burnAssist','Prograde');await page.fill('#burnSeconds','30');await page.fill('#burnThrottle','40');
 await page.click('#previewButton');await page.waitForFunction(()=>document.querySelector('#previewResult').textContent.includes('Native source'));
 assert.equal(await page.locator('#previewResult .orbit-shape ellipse').count(),2,'Native before/after bound orbits both need a size curve');
 assert.ok((await page.locator('#previewResult .burn-effect').innerText()).includes('Apoapsis rises'));
 for(const selector of ['#planButton','#previewButton','#burnSeconds','#burnThrottle','#burnAssist','#integrator'])assert.equal(await page.locator(selector).isDisabled(),false);
 const afterPreview=await native();assert.equal(afterPreview.epoch,initial.epoch);assert.equal(afterPreview.ship.fuel,initial.ship.fuel);assert.deepEqual(afterPreview.ship.control,initial.ship.control);
 await capture(page,'desktop-burn-preview.png','orbit');plannerViews.large=await visiblePlanner(page);
 await page.setViewportSize({width:1100,height:760});await capture(page,'desktop-burn-preview-compact.png','orbit');plannerViews.compact=await visiblePlanner(page);
 await page.setViewportSize({width:1440,height:960});
 await page.click('details.science > summary');
 await page.selectOption('#integrator','yoshida-fourth-order');await until(async()=>(await native()).settings.integrator==='yoshida-fourth-order','native coast setting change');
 await page.waitForFunction(()=>document.querySelector('#previewResult').classList.contains('preview-stale'));
 assert.equal(await page.locator('#previewResult .preview-heading').innerText(),'Flight changed. Calculate again.');
 await page.selectOption('#integrator',initial.settings.integrator);await until(async()=>(await native()).settings.integrator===initial.settings.integrator,'restore original coast method');
 await page.click('#closePlanner');
 await page.click('#markButton');await until(async()=>(await native()).checkpoint,'native checkpoint');const marked=await native();
 await page.click('[data-assist="Prograde"]');await until(async()=>(await native()).ship.control.attitude_mode==='Prograde','prograde input');
 const slider=await page.locator('#throttle').boundingBox();await page.mouse.click(slider.x+slider.width*.4,slider.y+slider.height*.5);
 await until(async()=>(await native()).ship.control.throttle>.3,'native throttle');await page.click('#playButton');
 await until(async()=>(await native()).ship.fuel<marked.ship.fuel-.5,'native propellant consumption');
 await capturePoweredFlight(page);
 await page.click('#cutButton');await until(async()=>(await native()).ship.control.throttle===0,'engine cut');
 await page.locator('#universe').click({position:{x:720,y:450}});await page.keyboard.press('Space');await until(async()=>(await native()).paused,'pause shortcut');
 const burned=await native();assert.ok(burned.ship.fuel<marked.ship.fuel);assert.ok(burned.ship.orbit.apoapsis>marked.ship.orbit.apoapsis);
 await page.click('#resetButton');await until(async()=>(await native()).epoch===marked.epoch,'checkpoint restore');assert.equal((await native()).ship.fuel,marked.ship.fuel);
 await page.click('[data-camera="map"]');await page.waitForFunction(()=>document.querySelector('#predictionLabel').textContent.includes('native t'));
 await capture(page,'desktop-native-map.png','map');
 const definition=JSON.parse(await readFile(path.join(root,'engine/data/authoring/pathfinder.scenario.json'),'utf8'));
 definition.id='authored.desktop-proof';definition.title='Ariadne desktop visual proof';definition.spacecraft_definitions[0].id='ariadne-electric';definition.spacecraft_definitions[0].display_name='Ariadne electric courier';definition.spacecraft_definitions[0].engines[0].max_thrust_n=2.4;definition.spacecraft[0].body_id='proof.ariadne';definition.spacecraft[0].definition_id='ariadne-electric';definition.focus_body_id='proof.ariadne';
 const downloadPromise=page.waitForEvent('download');await page.setInputFiles('#importFile',{name:'ariadne.scenario.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(definition))});
 const download=await downloadPromise;await download.saveAs(path.join(output,'preserved-flight.json'));
 await until(async()=>(await native()).scenario==='authored.desktop-proof','data-only scenario import');
 await page.selectOption('#craftSelect','proof.ariadne');await until(async()=>(await native()).ship.id==='proof.ariadne','new spacecraft selection');
 const authored=await native();assert.equal(authored.ship.name,'Ariadne electric courier');assert.equal(authored.ship.maxThrust,2.4);assert.equal(authored.crafts.length,1);assert.equal(authored.ship.visual.meshKind,3);assert.equal(authored.ship.visual.assetId,'example:pathfinder');await page.waitForFunction(()=>document.querySelector('#universe').dataset.meshKind==='3');
 await page.click('[data-camera="orbit"]');await capture(page,'desktop-authored-spacecraft.png','orbit');
 const authoredPixels=await scenePixels(page);assert.equal(authoredPixels.assetId,'example:pathfinder');assert.ok(authoredPixels.solidVertices>3000);assert.equal(authoredPixels.exhaustVertices,0);
 await page.setViewportSize({width:1100,height:760});await wait(200);const controlsCompact=await visibleControls(page);await capture(page,'desktop-compact-window.png','orbit');
 assert.deepEqual(httpErrors,[],'Every requested resource and native command must succeed');assert.deepEqual(errors,[],'Browser must not report JS or graphics failures');
 const report={renderer:'Chromium WebGL2, SwiftShader software rendering',nativeHost:'solarlab-desktop',checks:{previewUnchanged:true,plannerOpeningSinglePause:true,plannerOpeningLockedAcrossStatePolls:true,previewInputsLockedDuringNativeResponse:true,previewRemainsLockedAcrossStatePolls:true,previewCannotReopenWhilePending:true,reopenedPlannerClearsPreviousResponse:true,nativeOrbitShapeComparison:true,stalePreviewMarked:true,realFuelUsed:marked.ship.fuel-burned.ship.fuel,apoapsisRaisedM:burned.ship.orbit.apoapsis-marked.ship.orbit.apoapsis,cut:true,keyboardPitch:true,heldKeyRepeatAfterCutIgnored:true,opposingKeysCancel:true,fineControl:true,keyboardPause:true,checkpointRestored:true,nativeCoastPrediction:true,dataAuthoredSpacecraft:{id:authored.ship.id,name:authored.ship.name,thrustN:authored.ship.maxThrust,visualAsset:authored.ship.visual.assetId,meshKind:authored.ship.visual.meshKind,geometrySha256:authored.ship.visual.geometrySha256},previousFlightDownloaded:true},visual:{initialPixels,authoredPixels,controlsLarge,controlsCompact,plannerViews,captures,poweredCaptures},httpErrors,browserErrors:errors};
 await writeFile(path.join(output,'result.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));console.log(`Evidence: ${output}`);
}catch(e){await writeFile(path.join(output,'failure.json'),JSON.stringify({error:String(e),httpErrors,browserErrors:errors,captures,poweredCaptures,serverOutput},null,2));if(browser){const pages=browser.contexts().flatMap(c=>c.pages());if(pages[0]){await pages[0].screenshot({path:path.join(output,'failure.png')}).catch(()=>{});console.error(await pages[0].locator('body').innerText().catch(()=>''));}}console.error(serverOutput);throw e;
}finally{if(browser)await browser.close();server.kill('SIGTERM');}
