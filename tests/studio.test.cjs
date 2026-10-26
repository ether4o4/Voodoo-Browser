const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {JSDOM}=require('jsdom');const root=path.join(__dirname,'../app/src/main/assets');
const tick=()=>new Promise(r=>setTimeout(r,30));
async function fixture(overrides={},hash=''){
 const data={},calls=[],dom=new JSDOM(fs.readFileSync(path.join(root,'studio/index.html'),'utf8'),{url:'https://appassets.androidplatform.net/assets/studio/index.html'+hash,runScripts:'outside-only'});const w=dom.window;
 w.confirm=()=>true;w.Host={call:async(op,args={})=>{calls.push({op,args});if(overrides[op])return overrides[op](args,data);if(op==='get-store')return data[args.key]||null;if(op==='put-store'){if(args.expectedRevision!==undefined)assert.equal(args.expectedRevision,data[args.key]?._revision||0);data[args.key]=JSON.parse(JSON.stringify(args.value));return true;}if(op==='profiles')return [{id:'venice',name:'Venice AI',kind:'openai',base:'https://api.venice.ai/api/v1',model:'fixture-code-model',hasKey:true}];if(op==='context')return {url:'https://example.com/Create',title:'Fixture',...(args.includePage?{page:{text:'Explicitly shared'}}:{})};if(op==='chat')return {text:'Done.\n```javascript\n// ==UserScript==\n// @name AI draft\n// @match https://example.com/*\n// @grant none\n// ==/UserScript==\nwindow.fromAI=true;\n```',truncated:false};if(op==='run-script')return 'Ran test';return true;}};
 w.eval(fs.readFileSync(path.join(root,'pins.js'),'utf8'));w.eval(fs.readFileSync(path.join(root,'studio/engine.js'),'utf8'));w.eval(fs.readFileSync(path.join(root,'studio/workspace.js'),'utf8'));w.eval(fs.readFileSync(path.join(root,'studio/studio.js'),'utf8'));await tick();return {dom,w,data,calls,$:id=>w.document.getElementById(id)};
}
test('AI output becomes a disabled draft without a live execution request',async()=>{
 const f=await fixture();f.$('prompt').value='Make a script';f.$('chat-form').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await tick();await tick();assert.match(f.$('code').value,/window.fromAI/);assert.equal(f.$('script-enabled').checked,false);assert.equal(f.calls.some(c=>c.op==='run-script'),false);assert.equal(f.data.studio.scripts.length,0);assert.equal(f.data.studio.chat.messages.length,2);assert.equal(f.calls.find(c=>c.op==='chat').args.system.includes('Explicitly shared'),false);f.dom.window.close();
});
test('saving enables only the matching script and preserves its ID across edits',async()=>{
 const f=await fixture();f.$('code').value='// ==UserScript==\n// @name Test\n// @match https://example.com/*\n// @grant none\n// ==/UserScript==\nwindow.x=1;';f.$('script-name').value='Test';f.$('script-enabled').checked=true;f.$('code').dispatchEvent(new f.w.Event('input'));f.$('save-script').click();await tick();const id=f.data.studio.scripts[0].id;assert.equal(f.data.studio.scripts[0].enabled,true);f.$('code').value+='\nwindow.y=2;';f.$('code').dispatchEvent(new f.w.Event('input'));f.$('save-script').click();await tick();assert.equal(f.data.studio.scripts.length,1);assert.equal(f.data.studio.scripts[0].id,id);assert.match(f.data.studio.scripts[0].code,/window.y/);f.dom.window.close();
});
test('page context is opt-in and failed chat can be retried without duplicated history',async()=>{
 let first=true;const f=await fixture({chat:async()=>{if(first){first=false;throw Error('API 429');}return {text:'Success'};}});f.$('send-context').checked=true;f.$('prompt').value='Inspect page';f.$('chat-form').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await tick();await tick();assert.equal(f.data.studio.chat.messages.length,0);assert.equal(f.$('prompt').value,'Inspect page');assert.equal(f.$('send-context').checked,false);assert.match(f.calls.find(c=>c.op==='chat').args.system,/Explicitly shared/);f.$('chat-form').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await tick();await tick();assert.equal(f.data.studio.chat.messages.length,2);f.dom.window.close();
});
test('editing while AI is pending does not overwrite the newer draft',async()=>{
 let finish;const response=new Promise(r=>finish=r);const f=await fixture({chat:()=>response});f.$('prompt').value='Generate';f.$('chat-form').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await tick();f.$('code').value+='\n// New human edit';f.$('code').dispatchEvent(new f.w.Event('input'));finish({text:'```javascript\n// ==UserScript==\n// @name Generated\n// @match https://example.com/*\n// ==/UserScript==\nwindow.a=1;\n```'});await tick();await tick();assert.match(f.$('code').value,/New human edit/);assert.doesNotMatch(f.$('code').value,/window.a=1/);f.dom.window.close();
});
test('tool backup includes current workspace pins and names without provider keys',async()=>{
 const f=await fixture();f.data.pins=[{id:'pin-1',title:'Music',url:'https://example.com',group:'pink',lat:10,lon:20}];f.data.options={workspaceNames:{pink:'Music'}};f.$('backup').click();await tick();await tick();const exported=f.calls.find(c=>c.op==='export-file');assert.ok(exported);const backup=JSON.parse(exported.args.text);assert.deepEqual(backup.pins,f.data.pins);assert.equal(backup.options.workspaceNames.pink,'Music');assert.equal(backup.profiles,undefined);assert.equal(backup.keys,undefined);f.dom.window.close();
});
test('Settings opens on first load and reused section routes preserve unsaved drafts',async()=>{
 const f=await fixture({},'#settings');
 try{
  assert.equal(f.$('settings').hidden,false);assert.equal(f.$('chat').hidden,true);
  assert.equal(f.w.document.querySelector('[data-section="settings"]').classList.contains('active'),true);
  f.w.document.querySelector('[data-section="scripts"]').click();assert.equal(f.w.location.hash,'#workbench');
  const draft=f.$('code').value+'\n// Keep this unsaved edit';f.$('code').value=draft;f.$('code').dispatchEvent(new f.w.Event('input'));f.$('prompt').value='Keep my unsent chat';
  f.w.location.hash='#settings';await tick();assert.equal(f.$('settings').hidden,false);
  f.w.location.hash='#providers';await tick();assert.equal(f.$('providers').hidden,false);
  f.w.location.hash='#unknown-section';await tick();assert.equal(f.$('chat').hidden,false);assert.equal(f.w.location.hash,'#chat');
  f.w.VoodooUI.show('scripts');assert.equal(f.$('scripts').hidden,false);assert.equal(f.w.location.hash,'#workbench');
  assert.equal(f.$('code').value,draft);assert.equal(f.$('prompt').value,'Keep my unsent chat');
  assert.equal(f.calls.filter(c=>c.op==='get-store'&&c.args.key==='studio').length,1);
 }finally{f.dom.window.close();}
});
test('chat still sends when unrelated Studio revision saving fails',async()=>{
 const f=await fixture({'put-store':async()=>{throw Error('Workspace changed in another tab');}});
 try{f.$('prompt').value='Hello';f.$('chat-form').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await tick();await tick();assert.ok(f.calls.some(c=>c.op==='chat'));assert.match(f.$('messages').textContent,/Done/);assert.match(f.$('status').textContent,/not saved/);}finally{f.dom.window.close();}
});
test('cloud script sharing is off by default and explicit per request',async()=>{
 const f=await fixture();
 try{f.$('prompt').value='Hi';f.$('chat-form').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await tick();await tick();const chat=f.calls.find(c=>c.op==='chat');assert.doesNotMatch(chat.args.system,/My Voodoo script|Current draft/);f.$('send-script').checked=true;f.$('prompt').value='Explain my script';f.$('chat-form').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await tick();await tick();assert.match(f.calls.filter(c=>c.op==='chat').at(-1).args.system,/Current draft/);assert.equal(f.$('send-script').checked,false);}finally{f.dom.window.close();}
});

test('Workbench skills persist, activate in prompts and retain editor changes across routes',async()=>{
 const f=await fixture({},'#workbench');
 try{
  assert.equal(f.$('scripts').hidden,false);assert.equal(f.$('skill-mode').textContent,'Skill editor');f.$('skill-mode').click();
  f.$('skill-name').value='Reviewer';f.$('skill-instructions').value='Keep protected lines unchanged.';f.$('skill-instructions').dispatchEvent(new f.w.Event('input'));
  f.$('use-skill').click();await tick();await tick();assert.equal(f.data.studio.skills.length,1);assert.equal(f.data.studio.activeSkillId,f.data.studio.skills[0].id);assert.equal(f.$('chat').hidden,false);
  f.$('prompt').value='Review';f.$('chat-form').dispatchEvent(new f.w.Event('submit',{cancelable:true}));await tick();await tick();assert.match(f.calls.find(c=>c.op==='chat').args.system,/Keep protected lines unchanged/);
  f.w.VoodooUI.show('workbench');assert.equal(f.$('skill-instructions').value,'Keep protected lines unchanged.');
  f.$('clear-skill').click();await tick();assert.equal(f.data.studio.activeSkillId,null);
 }finally{f.dom.window.close();}
});
