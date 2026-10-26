(function(root){
  'use strict';
  const uid=()=>crypto.randomUUID(), copy=x=>JSON.parse(JSON.stringify(x));
  function skills(items=[]){
    if(!Array.isArray(items)||items.length>100)throw Error('Keep up to 100 skills.');
    const ids=new Set();return items.map(s=>{
      if(!s||typeof s.id!=='string'||!s.id||s.id.length>80||ids.has(s.id)||typeof s.name!=='string'||!s.name.trim()||s.name.length>120||typeof s.instructions!=='string'||s.instructions.length>20000)throw Error('Invalid skill. Use a name up to 120 characters and instructions up to 20,000.');
      ids.add(s.id);return {...s,name:s.name.trim()};
    });
  }
  function draft(s){if(!s)return {id:uid(),name:'',instructions:''};if(typeof s.id!=='string'||!s.id||s.id.length>80||typeof s.name!=='string'||s.name.length>120||typeof s.instructions!=='string'||s.instructions.length>20000)throw Error('Invalid skill draft.');return copy(s);}
  function normalize(state){state.skills=skills(state.skills);state.skillDraft=draft(state.skillDraft);if(!state.skills.some(s=>s.id===state.activeSkillId))state.activeSkillId=null;return state;}
  function prompt(state){const skill=state.skills?.find(s=>s.id===state.activeSkillId);return skill?'\nUser-selected skill: '+skill.name+'\n'+skill.instructions:'';}
  function voice(host,onText,onState){
    let epoch=0,conversation=false,active=false;
    const stop=()=>{epoch++;conversation=false;active=false;host.call('speech-stop').catch(()=>{});onState('Stopped',false);};
    const listen=async(autoSend=false)=>{
      const turn=++epoch;active=true;conversation=autoSend;onState('Listening…',conversation);
      try{const r=await host.call('speech-listen');if(turn!==epoch)return;active=false;const text=typeof r==='string'?r:r.text;if(text?.trim()){onText(text,autoSend);if(turn!==epoch)return;onState(autoSend?'Sending…':'Dictation ready. Review and send.',conversation);}else{conversation=false;onState('No speech heard.',false);}}
      catch(e){if(turn===epoch){active=false;conversation=false;onState(e.message||'Speech unavailable.',false);}}
    };
    const reply=async(text,expectedEpoch=epoch)=>{if(!conversation||expectedEpoch!==epoch)return;const turn=epoch;active=true;onState('Speaking…',true);try{await host.call('speech-speak',{text});if(turn===epoch&&conversation){active=false;await listen(true);}}catch(e){if(turn===epoch){active=false;conversation=false;onState(e.message||'Speech unavailable.',false);}}};
    return {listen,reply,stop,get token(){return epoch;},get active(){return active;},get conversation(){return conversation;}};
  }
  function mount({getState,persist,schedule,show,selectScript,send}){
    const $=id=>document.getElementById(id),state=()=>getState(),fail=e=>{$('skill-status').textContent=e.message||String(e);};normalize(state());
    const nav=document.createElement('div');nav.className='workspace-modes';nav.innerHTML='<button id="script-mode" type="button">Script editor</button><button id="skill-mode" type="button">Skill editor</button>';
    const editor=document.createElement('div');editor.id='script-editor';const section=$('scripts');for(const child of [...section.children].slice(1))editor.append(child);section.append(nav,editor);
    const pane=document.createElement('div');pane.id='skill-editor';pane.hidden=true;pane.innerHTML='<div class="row wrap"><button id="new-skill">New skill</button><button id="import-skill">Import</button><button id="export-skill">Export</button></div><label for="skill-name">Skill name</label><input id="skill-name" maxlength="120"><label for="skill-instructions">Instructions</label><textarea id="skill-instructions" rows="16" maxlength="20000"></textarea><div class="row wrap"><button id="save-skill" class="primary">Save skill</button><button id="use-skill">Use in chat</button><button id="clear-skill">Clear active skill</button><button id="delete-skill" class="danger">Delete skill</button></div><p id="skill-status" role="status"></p><input id="skill-file" type="file" accept=".json,application/json" hidden>';
    section.append(pane);
    const sidebar=document.createElement('aside');sidebar.className='saved-items';sidebar.innerHTML='<h2>Skills &amp; scripts</h2><div id="saved-skills"></div><div id="saved-scripts"></div><p id="active-skill" role="status"></p><button id="chat-clear-skill">Clear active skill</button>';
    const chat=$('chat'),layout=document.createElement('div');layout.className='chat-layout';const main=document.createElement('div');main.className='chat-main';while(chat.firstChild)main.append(chat.firstChild);layout.append(sidebar,main);chat.append(layout);
    const modes=skill=>{editor.hidden=skill;pane.hidden=!skill;$('script-mode').classList.toggle('active',!skill);$('skill-mode').classList.toggle('active',skill);};
    const renderDraft=()=>{$('skill-name').value=state().skillDraft.name;$('skill-instructions').value=state().skillDraft.instructions;};
    const render=()=>{
      $('saved-skills').replaceChildren();$('saved-scripts').replaceChildren();
      for(const s of state().skills){const b=document.createElement('button');b.textContent=s.name;b.className=s.id===state().activeSkillId?'active':'';b.onclick=()=>{state().skillDraft=copy(s);renderDraft();modes(true);show('workbench');schedule();};$('saved-skills').append(b);}
      for(const s of state().scripts){const b=document.createElement('button');b.textContent=s.name;b.onclick=()=>{selectScript(s);modes(false);show('workbench');};$('saved-scripts').append(b);}
      $('active-skill').textContent=state().skills.find(s=>s.id===state().activeSkillId)?.name||'No active skill';
    };
    const read=()=>{state().skillDraft={...state().skillDraft,name:$('skill-name').value,instructions:$('skill-instructions').value};schedule();};
    let skillWrites=Promise.resolve();
    const changeSkills=change=>{
      const operation=skillWrites.catch(()=>{}).then(async()=>{
        const current=state(),previousSkills=current.skills,previousActive=current.activeSkillId;
        try{const result=change(current);await persist();render();return result;}
        catch(e){current.skills=previousSkills;current.activeSkillId=previousActive;render();throw e;}
      });skillWrites=operation;return operation;
    };
    const save=async(activate=false)=>{
      read();const submitted=skills([copy(state().skillDraft)])[0];
      const saved=await changeSkills(current=>{const next=current.skills.filter(s=>s.id!==submitted.id);next.push(submitted);current.skills=skills(next);if(activate)current.activeSkillId=submitted.id;return submitted;});
      $('skill-status').textContent='Skill saved.';return saved;
    };
    $('script-mode').onclick=()=>modes(false);$('skill-mode').onclick=()=>modes(true);
    $('skill-name').oninput=read;$('skill-instructions').oninput=read;
    $('new-skill').onclick=()=>{state().skillDraft=draft();renderDraft();schedule();};
    $('save-skill').onclick=()=>save().catch(fail);
    $('use-skill').onclick=async()=>{try{await save(true);show('chat');}catch(e){fail(e);}};
    const clear=()=>changeSkills(current=>{current.activeSkillId=null;});$('clear-skill').onclick=()=>clear().catch(fail);$('chat-clear-skill').onclick=()=>clear().catch(fail);
    $('delete-skill').onclick=()=>{if(!confirm('Delete this saved skill?'))return;const id=state().skillDraft.id;state().skillDraft=draft();renderDraft();changeSkills(current=>{current.skills=current.skills.filter(s=>s.id!==id);if(current.activeSkillId===id)current.activeSkillId=null;}).catch(fail);};
    $('export-skill').onclick=()=>{try{read();const skill=skills([state().skillDraft])[0];Host.call('export-file',{filename:'Voodoo-skill.json',mime:'application/json',text:JSON.stringify({format:'voodoo-skill',version:1,skill},null,2)}).catch(fail);}catch(e){fail(e);}};
    $('import-skill').onclick=()=>$('skill-file').click();$('skill-file').onchange=async()=>{try{const file=$('skill-file').files[0];if(!file)return;if(file.size>100000)throw Error('Choose a skill under 100 KB.');const data=JSON.parse(await file.text());if(data.format!=='voodoo-skill'||data.version!==1)throw Error('Unsupported skill file.');state().skillDraft=skills([{...data.skill,id:uid()}])[0];renderDraft();schedule();$('skill-status').textContent='Imported as a draft. Review before saving.';}catch(e){fail(e);}finally{$('skill-file').value='';}};
    const controls=document.createElement('div');controls.className='voice-controls';controls.innerHTML='<button type="button" id="mic">Mic</button><button type="button" id="voice-conversation" aria-pressed="false">Voice conversation</button><button type="button" id="voice-stop">Stop</button><span id="voice-status" role="status"></span>';$('chat-form').append(controls);
    const draftGuard=()=>{$('voice-status').textContent='Send or clear your draft before starting Voice conversation.';};
    const speech=voice(Host,(text,autoSend)=>{
      const before=$('prompt').value;
      if(autoSend&&before.trim()){speech.stop();draftGuard();return;}
      $('prompt').value=before+(before&&!/\s$/.test(before)?'\n':'')+text;
      if(autoSend)send({preventDefault(){}});
    },(text,active)=>{$('voice-status').textContent=text;$('voice-conversation').setAttribute('aria-pressed',String(active));});
    $('mic').onclick=()=>{speech.stop();speech.listen(false);};$('voice-conversation').onclick=()=>{if(speech.conversation)speech.stop();else if($('prompt').value.trim())draftGuard();else speech.listen(true);};$('voice-stop').onclick=speech.stop;
    $('prompt').addEventListener('input',()=>{if(speech.conversation){speech.stop();draftGuard();}});
    addEventListener('pagehide',speech.stop);document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')speech.stop();});
    modes(false);renderDraft();render();return {render,stopVoice:speech.stop,reply:speech.reply,voiceToken:()=>speech.token,prompt:()=>prompt(state())};
  }
  const api={skills,draft,normalize,prompt,voice,mount};root.VoodooWorkspace=api;if(typeof module==='object')module.exports=api;
})(globalThis);
