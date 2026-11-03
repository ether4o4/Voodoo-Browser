const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{JSDOM}=require('jsdom');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/privacy/shield.js'),'utf8');
const windows=[];test.afterEach(()=>{for(const w of windows.splice(0))w.close();});
function page(seed=12345){
 const dom=new JSDOM('<!doctype html><body></body>',{url:'https://example.com/',runScripts:'outside-only',pretendToBeVisual:true}),w=dom.window;windows.push(w);
 w.RTCPeerConnection=function(){};w.DeviceMotionEvent=function(){};w.__originalAdd=w.Function.prototype.toString.call(w.EventTarget.prototype.addEventListener);
 w.eval(source.replace('__SEED__',String(seed)));return w;
}

test('device answers are generic and privacy signals are set',()=>{
 const w=page();
 assert.equal(w.navigator.language,'en-US');assert.deepEqual([...w.navigator.languages],['en-US','en']);
 assert.equal(w.navigator.hardwareConcurrency,4);assert.equal(w.navigator.doNotTrack,'1');assert.equal(w.navigator.globalPrivacyControl,true);
 assert.equal(w.screen.width,412);assert.equal(w.screen.height,915);
});

test('WebRTC and motion sensors are removed and cannot be restored',()=>{
 const w=page();
 assert.equal(w.RTCPeerConnection,undefined);assert.equal(w.DeviceMotionEvent,undefined);
 w.eval('try{window.RTCPeerConnection=function(){}}catch(e){}');assert.equal(w.RTCPeerConnection,undefined);
 let fired=false;w.addEventListener('devicemotion',()=>fired=true);w.dispatchEvent(new w.Event('devicemotion'));assert.equal(fired,false);
 let clicked=false;w.addEventListener('click',()=>clicked=true);w.dispatchEvent(new w.Event('click'));assert.equal(clicked,true);
});

test('time zone reads as UTC everywhere',()=>{
 const w=page();const d=new w.Date(Date.UTC(2026,10,3,13,5,9));
 assert.equal(d.getTimezoneOffset(),0);assert.equal(d.getHours(),13);assert.equal(d.getDate(),3);
 assert.equal(new w.Intl.DateTimeFormat().resolvedOptions().timeZone,'UTC');
 assert.match(d.toString(),/^Tue Nov 03 2026 13:05:09 GMT\+0000 \(Coordinated Universal Time\)$/);
 assert.ok(new w.Intl.DateTimeFormat() instanceof w.Intl.DateTimeFormat);
});

test('patched functions still look native',()=>{
 const w=page();
 assert.match(w.Date.prototype.getTimezoneOffset.toString(),/\[native code\]/);
 assert.match(w.Function.prototype.toString.toString(),/\[native code\]/);
 assert.equal(w.EventTarget.prototype.addEventListener.toString(),w.__originalAdd);
});

test('geolocation is denied',async()=>{
 const w=page();if(!w.navigator.geolocation)return;
 const err=await new Promise(r=>w.navigator.geolocation.getCurrentPosition(()=>r(null),r));assert.equal(err.code,1);
});
