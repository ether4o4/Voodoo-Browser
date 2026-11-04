const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{JSDOM}=require('jsdom');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/privacy/shield.js'),'utf8');
const windows=[];test.afterEach(()=>{for(const w of windows.splice(0))w.close();});
function page(seed=12345,setup=()=>{}){
 const dom=new JSDOM('<!doctype html><body></body>',{url:'https://example.com/',runScripts:'outside-only',pretendToBeVisual:true}),w=dom.window;windows.push(w);
 w.RTCPeerConnection=function(){};w.DeviceMotionEvent=function(){};w.__originalAdd=w.Function.prototype.toString.call(w.EventTarget.prototype.addEventListener);
 setup(w);w.eval(source.replace('__SEED__',String(seed)));return w;
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

test('frames created by the page are shielded before page code can use them',()=>{
 const w=page();
 const a=w.document.createElement('iframe');w.document.body.appendChild(a);
 const child=w[0];assert.equal(child.navigator.language,'en-US');assert.equal(new child.Date(0).getTimezoneOffset(),0);
 w.document.body.insertAdjacentHTML('beforeend','<iframe></iframe>');assert.equal(w[1].navigator.hardwareConcurrency,4);
 const div=w.document.createElement('div');w.document.body.appendChild(div);div.innerHTML='<iframe></iframe>';assert.equal(w[2].navigator.language,'en-US');
 const viaElement=w.document.createElement('iframe');w.document.body.append(viaElement);assert.equal(viaElement.contentWindow.Intl.DateTimeFormat().resolvedOptions().timeZone,'UTC');
});

test('workers start through the shield prelude',async()=>{
 const made=[],blobs=new Map();
 const w=page(7,w=>{w.Worker=class{constructor(url,options){made.push({url,options});}};w.URL.createObjectURL=b=>{const u='blob:https://example.com/'+blobs.size;blobs.set(u,b);return u;};});
 new w.Worker('/js/work.js');new w.Worker('mod.js',{type:'module'});
 assert.equal(made.length,2);assert.match(made[0].url,/^blob:/);
 const classic=await blobs.get(made[0].url).text(),module=await blobs.get(made[1].url).text();
 assert.match(classic,/voodooPrivacyShield/);assert.match(classic,/importScripts\("https:\/\/example\.com\/js\/work\.js"\)/);assert.match(classic,/\)\(7\);/);
 assert.match(module,/import "https:\/\/example\.com\/mod\.js";/);
});

test('service workers are disabled',async()=>{
 const w=page(1,w=>{w.ServiceWorkerContainer=class{register(){return Promise.resolve('registered');}getRegistrations(){return Promise.resolve(['r']);}};});
 const c=new w.ServiceWorkerContainer();
 await assert.rejects(c.register('/sw.js'));assert.equal((await c.getRegistrations()).length,0);
});
