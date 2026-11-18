// Run after cargo build -p solarlab-desktop. Actual native bridge and WebGL;
// no mocked world or JavaScript propagation. Needs playwright-core + Chromium.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
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
const errors=[],httpErrors=[];
async function visibleControls(page){
 const selectors=['#playButton','#cutButton','#throttle','#craftSelect','#centreButton','#fineButton','[data-axis="4"]'];
 const result=await page.evaluate(selectors=>Object.fromEntries(selectors.map(selector=>{const e=document.querySelector(selector),r=e.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2,top=document.elementFromPoint(x,y);return [selector,r.width>0&&r.height>0&&r.x>=0&&r.y>=0&&r.right<=innerWidth&&r.bottom<=innerHeight&&(top===e||e.contains(top))];})),selectors);
 assert.ok(Object.values(result).every(Boolean),`Core controls must be visible and reachable: ${JSON.stringify(result)}`);return result;
}
async function scenePixels(page){
 return page.evaluate(()=>{const c=document.querySelector('#universe'),gl=c.getContext('webgl2'),bytes=new Uint8Array(c.width*c.height*4);gl.readPixels(0,0,c.width,c.height,gl.RGBA,gl.UNSIGNED_BYTE,bytes);let colored=0;const shades=new Set();for(let i=0;i<bytes.length;i+=16){const r=bytes[i],g=bytes[i+1],b=bytes[i+2];if(Math.max(r,g,b)>40)colored++;shades.add(`${r>>4},${g>>4},${b>>4}`);}return {coloredSamples:colored,shades:shades.size,meshKind:c.dataset.meshKind,assetId:c.dataset.assetId,solidVertices:Number(c.dataset.solidVertices),exhaustVertices:Number(c.dataset.exhaustVertices)};});
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
 await wait(400);const controlsLarge=await visibleControls(page);const initialPixels=await scenePixels(page);assert.ok(initialPixels.coloredSamples>1000&&initialPixels.shades>24,`Visible spacecraft and planetary scene required: ${JSON.stringify(initialPixels)}`);assert.equal(initialPixels.exhaustVertices,0,'Paused idle spacecraft must not draw exhaust');await page.screenshot({path:path.join(output,'desktop-orbit.png')});
 await page.locator('#universe').click({position:{x:720,y:450}});
 await page.keyboard.down('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===-1,'keyboard pitch reaches native control');
 await page.keyboard.down('KeyS');await until(async()=>(await native()).ship.control.rotation_body.x===0,'opposing held keys cancel');
 await page.keyboard.up('KeyS');await until(async()=>(await native()).ship.control.rotation_body.x===-1,'one key release preserves other input');
 await page.keyboard.up('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===0,'keyboard release clears pitch');
 await page.click('#fineButton');await page.keyboard.down('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===-.2,'fine keyboard authority');await page.keyboard.up('KeyW');await until(async()=>(await native()).ship.control.rotation_body.x===0,'fine input release');await page.click('#fineButton');
 await page.click('[data-assist="Hold"]');await until(async()=>(await native()).ship.control.attitude_mode==='Hold','stabilise restore');
 const initial=await native();assert.equal(initial.paused,true);assert.equal(initial.ship.altitude,400000);
 await page.click('#planButton');await page.selectOption('#burnAssist','Prograde');await page.fill('#burnSeconds','30');await page.fill('#burnThrottle','40');await page.click('#previewButton');
 await page.waitForFunction(()=>document.querySelector('#previewResult').textContent.includes('Native source'));
 const afterPreview=await native();assert.equal(afterPreview.epoch,initial.epoch);assert.equal(afterPreview.ship.fuel,initial.ship.fuel);assert.deepEqual(afterPreview.ship.control,initial.ship.control);
 await page.screenshot({path:path.join(output,'desktop-burn-preview.png')});await page.click('#closePlanner');
 await page.click('#markButton');await until(async()=>(await native()).checkpoint,'native checkpoint');const marked=await native();
 await page.click('[data-assist="Prograde"]');await until(async()=>(await native()).ship.control.attitude_mode==='Prograde','prograde input');
 const slider=await page.locator('#throttle').boundingBox();await page.mouse.click(slider.x+slider.width*.4,slider.y+slider.height*.5);
 await until(async()=>(await native()).ship.control.throttle>.3,'native throttle');await page.click('#playButton');
 await until(async()=>(await native()).ship.fuel<marked.ship.fuel-.5,'native propellant consumption');await wait(250);
 assert.ok((await scenePixels(page)).exhaustVertices>0,'Powered native spacecraft must draw its authored exhaust');await page.screenshot({path:path.join(output,'desktop-powered-flight.png')});
 await page.click('#cutButton');await until(async()=>(await native()).ship.control.throttle===0,'engine cut');
 await page.locator('#universe').click({position:{x:720,y:450}});await page.keyboard.press('Space');await until(async()=>(await native()).paused,'pause shortcut');
 const burned=await native();assert.ok(burned.ship.fuel<marked.ship.fuel);assert.ok(burned.ship.orbit.apoapsis>marked.ship.orbit.apoapsis);
 await page.click('#resetButton');await until(async()=>(await native()).epoch===marked.epoch,'checkpoint restore');assert.equal((await native()).ship.fuel,marked.ship.fuel);
 await page.click('[data-camera="map"]');await page.waitForFunction(()=>document.querySelector('#predictionLabel').textContent.includes('native t'));
 assert.equal(await page.locator('[data-camera].active').count(),1);assert.equal(await page.locator('[data-camera].active').getAttribute('data-camera'),'map');await wait(200);await page.screenshot({path:path.join(output,'desktop-native-map.png')});
 const definition=JSON.parse(await readFile(path.join(root,'engine/data/authoring/pathfinder.scenario.json'),'utf8'));
 definition.id='authored.desktop-proof';definition.title='Ariadne desktop visual proof';definition.spacecraft_definitions[0].id='ariadne-electric';definition.spacecraft_definitions[0].display_name='Ariadne electric courier';definition.spacecraft_definitions[0].engines[0].max_thrust_n=2.4;definition.spacecraft[0].body_id='proof.ariadne';definition.spacecraft[0].definition_id='ariadne-electric';definition.focus_body_id='proof.ariadne';
 const downloadPromise=page.waitForEvent('download');await page.setInputFiles('#importFile',{name:'ariadne.scenario.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(definition))});
 const download=await downloadPromise;await download.saveAs(path.join(output,'preserved-flight.json'));
 await until(async()=>(await native()).scenario==='authored.desktop-proof','data-only scenario import');
 await page.selectOption('#craftSelect','proof.ariadne');await until(async()=>(await native()).ship.id==='proof.ariadne','new spacecraft selection');
 const authored=await native();assert.equal(authored.ship.name,'Ariadne electric courier');assert.equal(authored.ship.maxThrust,2.4);assert.equal(authored.crafts.length,1);assert.equal(authored.ship.visual.meshKind,3);assert.equal(authored.ship.visual.assetId,'example:pathfinder');await page.waitForFunction(()=>document.querySelector('#universe').dataset.meshKind==='3');
 await page.click('[data-camera="orbit"]');await wait(300);await page.screenshot({path:path.join(output,'desktop-authored-spacecraft.png')});
 const authoredPixels=await scenePixels(page);assert.equal(authoredPixels.assetId,'example:pathfinder');assert.ok(authoredPixels.solidVertices>3000);assert.equal(authoredPixels.exhaustVertices,0);
 await page.setViewportSize({width:1100,height:760});await wait(200);const controlsCompact=await visibleControls(page);await page.screenshot({path:path.join(output,'desktop-compact-window.png')});
 assert.deepEqual(httpErrors,[],'Every requested resource and native command must succeed');assert.deepEqual(errors,[],'Browser must not report JS or graphics failures');
 const report={renderer:'Chromium WebGL2, SwiftShader software rendering',nativeHost:'solarlab-desktop',checks:{previewUnchanged:true,realFuelUsed:marked.ship.fuel-burned.ship.fuel,apoapsisRaisedM:burned.ship.orbit.apoapsis-marked.ship.orbit.apoapsis,cut:true,keyboardPitch:true,opposingKeysCancel:true,fineControl:true,keyboardPause:true,checkpointRestored:true,nativeCoastPrediction:true,dataAuthoredSpacecraft:{id:authored.ship.id,name:authored.ship.name,thrustN:authored.ship.maxThrust,visualAsset:authored.ship.visual.assetId,meshKind:authored.ship.visual.meshKind,geometrySha256:authored.ship.visual.geometrySha256},previousFlightDownloaded:true},visual:{initialPixels,authoredPixels,controlsLarge,controlsCompact},httpErrors,browserErrors:errors};
 await writeFile(path.join(output,'result.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));console.log(`Evidence: ${output}`);
}catch(e){await writeFile(path.join(output,'failure.json'),JSON.stringify({error:String(e),httpErrors,browserErrors:errors,serverOutput},null,2));if(browser){const pages=browser.contexts().flatMap(c=>c.pages());if(pages[0]){await pages[0].screenshot({path:path.join(output,'failure.png')}).catch(()=>{});console.error(await pages[0].locator('body').innerText().catch(()=>''));}}console.error(serverOutput);throw e;
}finally{if(browser)await browser.close();server.kill('SIGTERM');}
