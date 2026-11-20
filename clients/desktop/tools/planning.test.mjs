import assert from 'node:assert/strict';
import test from 'node:test';
import {orbitShape,apsisChange,countdown,apsisTiming,navigationHeading,directionGeometry,burnAttitude} from '../web/planning.js';

const near=(actual,expected,tolerance=1e-12)=>assert.ok(Math.abs(actual-expected)<=tolerance,`${actual} differs from ${expected}`);
const guidance=(regime,p=null,a=null)=>({regime,timeToPeriapsisSeconds:p,timeToApoapsisSeconds:a,circularisationDeltaVMps:20});
const ship=()=>({position:[7000000,0,0],velocity:[0,0,7500],attitude:{w:1,x:0,y:0,z:0},spin:[0,0,0],maxThrust:90000,fuel:2500});

test('common-scale circles and ellipse endpoints preserve the native apsis radii',()=>{
 const shape=orbitShape(6000000,400000,400000,400000,2000000);
 near(shape.hostRadius,.75);near(shape.before.centreX,0);near(shape.before.major,.8);near(shape.before.minor,.8);
 near(shape.after.centreX,-.1);near(shape.after.major,.9);near(shape.after.minor,Math.sqrt(.8));
 near(shape.after.centreX+shape.after.major,.8);near(shape.after.centreX-shape.after.major,-1);
 near(shape.left,1);near(shape.right,.8);near(shape.halfHeight,Math.sqrt(.8));
});

test('open, invalid and surface-crossing orbits are never invented as closed curves',()=>{
 const escape=orbitShape(6,1,2,1,null);assert.ok(escape.before);assert.equal(escape.after,null);
 assert.equal(orbitShape(6,1,null,1,null),null);assert.equal(orbitShape(6,2,1,NaN,4),null);
 assert.equal(orbitShape(Infinity,1,2,1,2),null);assert.equal(orbitShape(-1,2,3,2,3),null);
 assert.equal(orbitShape(6,-6,8,null,null),null);
 const crossing=orbitShape(6,-2,3,null,null);assert.ok(crossing.before);assert.ok(crossing.hostRadius>crossing.right-1e-12);
 near(crossing.before.centreX+crossing.before.major,4/9);
});

test('extreme finite radii are normalised before products and sums',()=>{
 const huge=orbitShape(1e307,2e307,1e308,4e307,1.2e308);
 for(const ellipse of [huge.before,huge.after])for(const value of Object.values(ellipse))assert.ok(Number.isFinite(value));
 assert.equal(orbitShape(1e308,1e308,1e308,null,null),null);
});

test('timing distinguishes exact passage, rounding and missing estimates',()=>{
 assert.equal(countdown(0),'now');assert.equal(countdown(.2),'in <1 s');assert.equal(countdown(59.2),'in 1 m 0 s');assert.equal(countdown(3599.2),'in 1 h 0 m');assert.equal(countdown(90000),'in 1.0 d');
 for(const value of [null,undefined,-1,NaN,Infinity])assert.equal(countdown(value),null);
 assert.equal(navigationHeading(guidance('elliptic',500,100)),'Apoapsis in 1 m 40 s');
 assert.equal(navigationHeading(guidance('elliptic',0,100)),'Periapsis now');
 assert.equal(navigationHeading(guidance('nearCircular')),'Nearly circular · no distinct apsis');
 assert.equal(apsisTiming(guidance('nearCircular'),true),'no distinct apsis');
 assert.equal(navigationHeading(guidance('hyperbolic',100)),'Periapsis in 1 m 40 s');
 assert.equal(apsisTiming(guidance('hyperbolic'),false),'no apoapsis');
 assert.equal(navigationHeading(guidance('hyperbolic')),'Open trajectory · no future periapsis estimate');
 assert.equal(navigationHeading(guidance('nearParabolic')),'Apsis timing unavailable near escape speed');
 assert.equal(navigationHeading(guidance('elliptic')),'Apsis timing unavailable');
});

test('change explanations do not turn absent values or rounding into zero burns',()=>{
 assert.equal(apsisChange('Periapsis',100,120),'Periapsis rises 20 m');
 assert.equal(apsisChange('Periapsis',100,-100),'Periapsis falls 200 m');
 assert.equal(apsisChange('Periapsis',100,100.4),'Periapsis changes by less than 1 m');
 assert.equal(apsisChange('Apoapsis',100,null),'Apoapsis has no finite estimate after the burn');
 assert.equal(apsisChange('Apoapsis',null,100),'Apoapsis becomes 100 m');
 assert.equal(apsisChange('Apoapsis',-1e308,1e308),'Apoapsis changes beyond the display range');
});

test('director distinguishes forward, aft and degenerate orbital directions',()=>{
 assert.equal(directionGeometry(ship(),'Prograde').aligned,true);
 const aft=directionGeometry(ship(),'Retrograde');near(aft.errorDegrees,180);assert.equal(aft.behind,true);near(aft.markerY,1);assert.equal(aft.aligned,false);
 const radial=directionGeometry(ship(),'RadialOut');near(radial.errorDegrees,90);near(radial.markerX,1);
 assert.equal(directionGeometry({...ship(),velocity:[7500,0,0]},'Normal'),null);
 assert.equal(directionGeometry({...ship(),attitude:{w:0,x:0,y:0,z:0}},'Prograde'),null);
 const rotated={...ship(),attitude:{w:Math.SQRT1_2,x:0,y:Math.SQRT1_2,z:0}};
 assert.equal(directionGeometry(rotated,'RadialOut').aligned,true);
 const opposite={...rotated,attitude:Object.fromEntries(Object.entries(rotated.attitude).map(([k,v])=>[k,-v]))};
 assert.deepEqual(directionGeometry(rotated,'Prograde'),directionGeometry(opposite,'Prograde'));
});

test('burn attitude does not call a manual reference marker an aligned burn',()=>{
 assert.equal(burnAttitude(ship(),'Prograde',.5),'Nose aligned with prograde');
 assert.equal(burnAttitude(ship(),'Retrograde',.5),'180° to turn · thrust starts before alignment');
 assert.equal(burnAttitude(ship(),'Manual',.5),'Current nose direction · existing spin continues');
 assert.equal(burnAttitude(ship(),'Hold',.5),'Current nose direction · Stabilise damps spin');
 assert.equal(burnAttitude(ship(),'Prograde',0),'Coast preview · main engine off');
 assert.equal(burnAttitude({...ship(),fuel:0},'Prograde',.5),'No propellant remains for this burn');
 assert.equal(burnAttitude({...ship(),spin:[Math.PI/180,0,0]},'Prograde',.5),'Nose aligned · still rotating 1.0°/s');
});
