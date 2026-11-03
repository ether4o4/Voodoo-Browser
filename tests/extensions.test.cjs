const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{JSDOM}=require('jsdom');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/extensions/chrome-api.js'),'utf8');
const plain=x=>JSON.parse(JSON.stringify(x));
const tick=()=>new Promise(r=>setTimeout(r,30));
const windows=[];test.afterEach(()=>{for(const w of windows.splice(0))w.close();});
const manifest={manifest_version:3,name:'Fixture',version:'1.0',permissions:['storage','tabs']};
function fixture({context='content',url='https://example.com/page',entries='[]',handler=()=>null,readyState}={}){
 const dom=new JSDOM('<!doctype html><html><head></head><body></body></html>',{url,runScripts:'outside-only'}),w=dom.window,calls=[];windows.push(w);
 if(readyState)Object.defineProperty(w.document,'readyState',{configurable:true,get:readyState});
 const name=context==='content'?'VoodooExt':'VoodooExtPage';
 w[name]={postMessage(raw){const q=JSON.parse(raw);calls.push(q);if(!q.id)return;let data=q.op==='hello'?{tabId:3,frameId:0}:handler(q);
  queueMicrotask(()=>w[name].onmessage({data:JSON.stringify(data instanceof Error?{id:q.id,ok:false,error:data.message}:{id:q.id,ok:true,data:data===undefined?null:data})}));}};
 const config={id:'a'.repeat(32),base:'https://'+'a'.repeat(32)+'.ext.voodoo/',manifest,messages:{greeting:{message:'Hello $1 from $who$',placeholders:{who:{content:'Voodoo'}}}},context,uiLang:'en-US'};
 w.eval(source+'\nVoodooExtension('+JSON.stringify(config)+','+entries+');');
 return {w,calls,emit:m=>w[name].onmessage({data:JSON.stringify(m)})};
}

test('content scripts get the content-script API surface only',()=>{
 const {w}=fixture();
 assert.equal(w.chrome.runtime.id,'a'.repeat(32));
 assert.equal(w.chrome.runtime.getURL('/icons/a.png'),'https://'+'a'.repeat(32)+'.ext.voodoo/icons/a.png');
 assert.equal(w.chrome.runtime.getManifest().name,'Fixture');
 assert.equal(w.chrome.i18n.getMessage('greeting',['there']),'Hello there from Voodoo');
 assert.equal(w.chrome.i18n.getMessage('@@extension_id'),'a'.repeat(32));
 assert.equal(w.browser,w.chrome);
 assert.equal(w.chrome.tabs,undefined);assert.equal(w.chrome.scripting,undefined);
});

test('extension pages get tabs, scripting, alarms and network rule APIs',()=>{
 const {w}=fixture({context:'background',url:'https://'+'a'.repeat(32)+'.ext.voodoo/_generated_background_page.html'});
 for(const ns of ['tabs','windows','scripting','action','browserAction','contextMenus','alarms','declarativeNetRequest','cookies','offscreen','permissions','notifications','webNavigation'])assert.ok(w.chrome[ns],ns);
 assert.equal(typeof w.importScripts,'function');
});

test('runtime.sendMessage supports promises and callbacks with lastError',async()=>{
 const {w,calls}=fixture({handler:q=>q.op==='send-message'?(q.message.fail?new Error('Could not establish connection. Receiving end does not exist.'):{echo:q.message.n}):null});
 assert.deepEqual(plain(await w.chrome.runtime.sendMessage({n:1})),{echo:1});
 assert.equal(calls.find(c=>c.op==='send-message').target,'runtime');
 let seen;w.chrome.runtime.sendMessage({fail:true},r=>{seen=[r,w.chrome.runtime.lastError&&w.chrome.runtime.lastError.message];});await tick();
 assert.equal(seen[0],undefined);assert.match(seen[1],/Receiving end does not exist/);assert.equal(w.chrome.runtime.lastError,undefined);
});

test('onMessage listeners answer synchronously, asynchronously or not at all',async()=>{
 const {w,calls,emit}=fixture();
 w.chrome.runtime.onMessage.addListener((m,s,send)=>{if(m==='sync')send('now');if(m==='later'){setTimeout(()=>send('done'),5);return true;}if(m==='promise')return Promise.resolve('p');});
 emit({event:'message',mid:'1',message:'sync',sender:{id:'x'}});emit({event:'message',mid:'2',message:'later',sender:{id:'x'}});emit({event:'message',mid:'3',message:'none',sender:{id:'x'}});emit({event:'message',mid:'4',message:'promise',sender:{id:'x'}});
 await tick();const r=Object.fromEntries(calls.filter(c=>c.op==='respond').map(c=>[c.mid,c]));
 assert.equal(r[1].response,'now');assert.equal(r[2].response,'done');assert.equal(r[3].none,true);assert.equal(r[4].response,'p');
});

test('storage areas round-trip and onChanged fires from native events',async()=>{
 const store={};const {w,emit}=fixture({handler:q=>{if(q.op==='storage-set')Object.assign(store,q.items);if(q.op==='storage-get')return Object.fromEntries(Object.entries(store).filter(([k])=>q.keys===null||[].concat(q.keys).includes(k)));return null;}});
 await w.chrome.storage.local.set({a:1,b:{c:2}});assert.deepEqual(plain(await w.chrome.storage.local.get('b')),{b:{c:2}});
 let change;w.chrome.storage.onChanged.addListener((c,area)=>change=[c,area]);emit({event:'storage',area:'local',changes:{a:{oldValue:1,newValue:2}}});
 assert.deepEqual(plain(change),[{a:{oldValue:1,newValue:2}},'local']);
});

test('ports connect, exchange messages and disconnect',async()=>{
 const {w,calls,emit}=fixture();const port=w.chrome.runtime.connect({name:'chan'});const got=[];port.onMessage.addListener(m=>got.push(m));
 const open=calls.find(c=>c.op==='connect');assert.equal(open.name,'chan');port.postMessage({hi:1});assert.deepEqual(calls.at(-1).message,{hi:1});
 emit({event:'port-message',portId:open.portId,message:'back'});assert.deepEqual(got,['back']);
 let closed=false;port.onDisconnect.addListener(()=>closed=true);emit({event:'port-disconnect',portId:open.portId});assert.ok(closed);
 assert.throws(()=>port.postMessage(1),/disconnected/);
 let incoming;w.chrome.runtime.onConnect.addListener(p=>incoming=p);emit({event:'connect',portId:'p2',name:'from-bg',sender:{id:'x'}});assert.equal(incoming.name,'from-bg');
});

test('content script entries respect matches, excludes, frames and run_at',async()=>{
 let state='loading';
 const entries=`[{matches:['^https?:\\\\/\\\\/example\\\\.com(?::\\\\d+)?\\\\/.*$'],excludeMatches:[],includeGlobs:[],excludeGlobs:[],allFrames:false,runAt:'document_start',css:'body{color:red}',run:function(){window.order=(window.order||[]).concat('start');}},
  {matches:['^https?:\\\\/\\\\/example\\\\.com(?::\\\\d+)?\\\\/.*$'],excludeMatches:[],includeGlobs:[],excludeGlobs:[],allFrames:false,runAt:'document_end',css:'',run:function(){window.order=(window.order||[]).concat('end');}},
  {matches:['^https?:\\\\/\\\\/other\\\\.com\\\\/.*$'],excludeMatches:[],includeGlobs:[],excludeGlobs:[],allFrames:false,runAt:'document_start',css:'',run:function(){window.order=(window.order||[]).concat('other');}}]`;
 const {w}=fixture({entries,readyState:()=>state});
 assert.deepEqual(plain(w.order),['start']);
 state='interactive';w.document.dispatchEvent(new w.Event('DOMContentLoaded'));assert.deepEqual(plain(w.order),['start','end']);
 const styles=[...w.document.querySelectorAll('style')].map(s=>s.textContent).concat((w.document.adoptedStyleSheets||[]).map(()=>'adopted'));assert.ok(styles.length>=1);
});

test('executeScript func results report through the hidden world callback',async()=>{
 const {w,calls}=fixture();w.__voodooExecDone('e1',{ok:true});
 assert.deepEqual(calls.at(-1),{op:'exec-result',eid:'e1',result:{ok:true},url:'https://example.com/page',top:true});
 assert.equal(Object.keys(w).includes('__voodooExecDone'),false);
});

test('alarms fire on schedule from extension pages',async()=>{
 const {w}=fixture({context:'background',url:'https://'+'a'.repeat(32)+'.ext.voodoo/bg.html'});const fired=[];
 w.chrome.alarms.onAlarm.addListener(a=>fired.push(a.name));w.chrome.alarms.create('soon',{when:Date.now()+5});
 assert.equal((await w.chrome.alarms.getAll()).length,1);await tick();assert.deepEqual(fired,['soon']);assert.equal((await w.chrome.alarms.getAll()).length,0);
});
