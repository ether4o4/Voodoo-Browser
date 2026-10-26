const test=require('node:test'),assert=require('node:assert/strict');
const W=require('../app/src/main/assets/studio/workspace.js');
const skill={id:'skill-1',name:'Editing',instructions:'Preserve approved lines.'};
test('skill validation, prompt selection and old-state migration preserve data',()=>{
 const s={scripts:[{id:'new-engine-script'}],skills:[skill],skillDraft:{...skill,name:'Unsaved'},activeSkillId:skill.id};
 W.normalize(s);assert.equal(s.scriptDraft,undefined);assert.equal(s.skillDraft.name,'Unsaved');assert.match(W.prompt(s),/Preserve approved lines/);assert.equal(s.scripts[0].id,'new-engine-script');
 const old={scripts:[]};W.normalize(old);assert.deepEqual(old.skills,[]);assert.equal(old.activeSkillId,null);
 for(const items of [[skill,skill],[{...skill,name:''}],[{...skill,instructions:'x'.repeat(20001)}],Array.from({length:101},(_,i)=>({...skill,id:String(i)}))])assert.throws(()=>W.skills(items));
 assert.equal(W.prompt({...s,activeSkillId:null}),'');
});
test('dictation never auto-sends and stale recognition is ignored after stop',async()=>{
 const pending=[],texts=[],host={call(op){if(op==='speech-listen')return new Promise(r=>pending.push(r));return Promise.resolve(true);}};
 const v=W.voice(host,(...x)=>texts.push(x),()=>{});const first=v.listen(false);pending.shift()('Draft');await first;assert.deepEqual(texts,[['Draft',false]]);
 const second=v.listen(true);v.stop();pending.shift()('Stale');await second;assert.equal(texts.length,1);assert.equal(v.conversation,false);
});
test('explicit conversation resumes listening after speech but stop invalidates pending output',async()=>{
 const listeners=[],speakers=[],texts=[],host={call(op){if(op==='speech-listen')return new Promise(r=>listeners.push(r));if(op==='speech-speak')return new Promise(r=>speakers.push(r));return Promise.resolve(true);}};
 const v=W.voice(host,(...x)=>texts.push(x),()=>{});const first=v.listen(true);listeners.shift()('Ask');await first;assert.deepEqual(texts,[['Ask',true]]);
 const reply=v.reply('Answer');v.stop();speakers.shift()(true);await reply;assert.equal(listeners.length,0);
});
