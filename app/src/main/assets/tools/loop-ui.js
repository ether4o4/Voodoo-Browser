(function(){
  'use strict';
  if(globalThis.VoodooLoopInstalled)return;
  globalThis.VoodooLoopInstalled=true;
  const tools=globalThis.WorkbenchSunoTools;
  if(!tools||!globalThis.WorkbenchLoop)return;
  const {ext,host,shadow,panel,visible,E,setPanel}=tools;
  const style=E('style');style.textContent='.loop{border-top:1px solid #ccd0c2;margin-top:14px;padding-top:12px}.loop summary{cursor:pointer;font-weight:650;font-size:13px;padding:7px 0}.loop label{display:block;font-size:11px;margin:10px 0 5px}.loop input{box-sizing:border-box;width:100%;border:1px solid #ccd0c2;border-radius:6px;background:#fffef9;color:#242723;padding:9px;font:14px system-ui}.loop .two{display:grid;grid-template-columns:1fr 1fr;gap:9px}.loop .start{background:#242723;color:#fffef9}.loop .stop{background:#a33325;color:white;border-color:#a33325}.loop .loop-status{font-size:11px;line-height:1.6;white-space:pre-wrap;margin:10px 0;color:#7b391e}.loop .learn{font-size:10px;padding:6px 4px}.loop .setup{display:grid;grid-template-columns:1fr 1fr 1fr;gap:5px;margin:10px 0}.loop .indicator{font:10px ui-monospace,monospace;color:#65705b;margin:7px 0}.loop small{font-size:10px;color:#65705b}.loop .row{margin-bottom:0}.loop code{font-size:10px;overflow-wrap:anywhere}.loop .reset{font-size:10px;border:0;padding:3px 0;min-height:30px;background:transparent}.loop-status[data-active=true]{color:#374d28}';shadow.append(style);
  const details=E('details');details.className='loop';details.append(E('summary','Auto-create loop'));
  const intro=E('p','Start one song manually. Teach the page indicators below, then turn on the loop while that song is loading.');details.append(intro);
  const setup=E('div');setup.className='setup';const createPick=E('button','Pick Create'),busyPick=E('button','Pick loading'),readyPick=E('button','Pick completed');for(const b of [createPick,busyPick,readyPick])b.className='learn';setup.append(createPick,busyPick,readyPick);details.append(setup);
  const indicator=E('div');indicator.className='indicator';details.append(indicator);
  const two=E('div');two.className='two';
  function input(label,value,id){const wrap=E('label',label),field=E('input');field.type='number';field.value=value;field.min='1';field.max='3600';field.id=id;wrap.append(field);return {wrap,field};}
  const min=input('Min pause / seconds','5','fourga-delay-min'),max=input('Max pause / seconds','15','fourga-delay-max');two.append(min.wrap,max.wrap);details.append(two);const limit=input('Extra runs / 0 = until stopped','0','fourga-run-limit');limit.field.min='0';limit.field.max='10000';details.append(limit.wrap);
  const actions=E('div');actions.className='row';const start=E('button','Start loop'),stop=E('button','Stop');start.className='start';stop.className='stop';stop.disabled=true;actions.append(start,stop);details.append(actions);
  const loopStatus=E('div','Off.');loopStatus.className='loop-status';loopStatus.setAttribute('role','status');details.append(loopStatus,E('small','Uses your Suno credits. Stop prevents future runs. Keep this tab open; phone sleep can pause the loop.'));
  const reset=E('button','Reset page setup');reset.className='reset';details.append(reset);panel.append(details);
  let learned={create:null,busy:null,ready:null},createElement=null,picker=null,observer=null,timer=null,route='',lastTick=0,sampleError='';
  function nodesFor(descriptor){if(!descriptor)return [];let all=[];try{all=[...document.querySelectorAll(descriptor.selector)];}catch{throw new Error('The page marker is invalid. Reset page setup and pick again.');}return all.filter(el=>visible(el)&&!host.contains(el)&&(!descriptor.text||el.textContent.trim()===descriptor.text)&&(!descriptor.duration||/^\d{1,2}:\d{2}$/.test(el.textContent.trim())));}
  function nodeCount(descriptor){return nodesFor(descriptor).length;}
  function descriptorFor(el,kind){
    for(const attr of ['data-status','data-state']){const value=el.getAttribute(attr);if(value&&((kind==='busy'&&/^(loading|pending|queued|generating|processing)$/.test(value))||(kind==='ready'&&/^(complete|completed|success|ready)$/.test(value))))return {selector:'['+attr+'="'+CSS.escape(value)+'"]'};}
    if(kind==='busy'){
      const b=el.closest('[aria-busy="true"],[role="progressbar"]');if(b)return {selector:b.hasAttribute('aria-busy')?'[aria-busy="true"]':'[role="progressbar"]'};
      const animated=[el,...el.querySelectorAll('*')].find(e=>[...e.classList].some(c=>/animate-(spin|pulse)/.test(c)));if(animated){const c=[...animated.classList].find(c=>/animate-(spin|pulse)/.test(c));return {selector:'.'+CSS.escape(c)};}
    }
    const attr=el.getAttribute('data-testid');if(attr)return {selector:'[data-testid="'+CSS.escape(attr)+'"]',...((kind==='ready'&&/^\d{1,2}:\d{2}$/.test(el.textContent.trim()))?{duration:true}:{text:el.textContent.trim()||undefined})};
    let selector=el.tagName.toLowerCase();const classes=[...el.classList].filter(c=>c.length<70&&!/^(active|selected|focus|hover)/.test(c)).slice(0,3);selector+=classes.map(c=>'.'+CSS.escape(c)).join('');
    const text=el.textContent.trim();
    if(kind==='ready'&&/^\d{1,2}:\d{2}$/.test(text))return {selector,duration:true};
    if(text&&text.length<=50)return {selector,text};
    if(classes.length)return {selector};
    throw new Error('Choose the small loading label, status icon, or finished duration—not the entire card.');
  }
  function createButton(){if(createElement?.isConnected)return createElement;if(learned.create){const candidates=nodesFor(learned.create);if(candidates.length===1)return candidates[0];}return null;}
  function updateIndicators(){try{indicator.textContent='Create: '+(createButton()?'set':'pick')+' · Loading: '+(learned.busy?nodeCount(learned.busy):'pick')+' · Completed: '+(learned.ready?nodeCount(learned.ready):'pick');}catch(e){indicator.textContent=e.message;}}
  function saveSetup(){ext.storage.local.set({workbenchLoopSetup:{learned,min:min.field.value,max:max.field.value,limit:limit.field.value}}).catch(()=>{});}
  function detach(){observer?.disconnect();observer=null;clearInterval(timer);timer=null;}
  function checkErrors(){
    if(location.pathname!==route&&route)return 'The page changed. Restart the loop on the create page.';
    if(!navigator.onLine)return 'Connection lost. Restart after the page reconnects.';
    const alerts=[...document.querySelectorAll('[role="alert"],[role="alertdialog"],[role="dialog"]')].filter(visible);
    const text=alerts.map(a=>a.textContent).join(' ');
    if(/not enough credits|insufficient credits|out of credits|purchase credits|buy more credits|upgrade to continue/i.test(text))return 'Suno needs credits. The loop stopped.';
    if(/sign in|log in|login required|session expired/i.test(text))return 'Suno needs you to sign in. The loop stopped.';
    if(/something went wrong|generation failed|unable to generate|try again later|too many requests|rate limit/i.test(text))return 'Suno reported an error. The loop stopped.';
    const challenge=[...document.querySelectorAll('iframe[title*="challenge" i],iframe[title*="captcha" i],iframe[src*="captcha"]')].find(visible);
    if(challenge)return 'A page verification needs your attention. The loop stopped.';
    return '';
  }
  const runner=new globalThis.WorkbenchLoop.CompletionLoop({
    sample:()=>{const button=createButton();const error=checkErrors()||sampleError;return {busy:nodeCount(learned.busy),ready:nodeCount(learned.ready),canSubmit:!!button&&visible(button)&&!button.disabled&&button.getAttribute('aria-disabled')!=='true',error};},
    submit:async isCurrent=>{if(!isCurrent())return;const b=createButton();if(!b||!visible(b)||b.disabled||b.getAttribute('aria-disabled')==='true')throw new Error('The selected Create button is unavailable.');if(isCurrent())b.click();},
    onChange:info=>{loopStatus.textContent=info.message+'\nExtra runs started: '+info.submitted;const active=['waiting','awaiting','cooldown'].includes(info.state);loopStatus.dataset.active=String(active);start.disabled=active;stop.disabled=!active;for(const b of [createPick,busyPick,readyPick,reset,min.field,max.field,limit.field])b.disabled=active;if(!active)detach();else if(info.state==='cooldown')updateIndicators();}
  });
  function unpick(){if(picker){document.removeEventListener('click',picker,true);document.removeEventListener('keydown',escapePick,true);picker=null;}document.documentElement.style.cursor='';}
  function escapePick(e){if(e.key==='Escape'){unpick();setPanel(true);loopStatus.textContent='Selection cancelled.';}}
  function pick(kind){unpick();setPanel(false);document.documentElement.style.cursor='crosshair';picker=e=>{if(e.composedPath().includes(host))return;e.preventDefault();e.stopImmediatePropagation();let el=e.target;if(kind==='create'){el=el.closest('button,[role="button"],input[type="submit"]');if(!el)return;}
      try{if(kind==='create'){const label=(el.getAttribute('aria-label')||el.textContent||el.value||'').trim();if(!/^(create|generate|create song|generate song|create music)(\s|$)/i.test(label))throw new Error('Pick the button labeled Create or Generate.');createElement=el;learned.create={selector:el.tagName.toLowerCase()+(el.getAttribute('data-testid')?'[data-testid="'+CSS.escape(el.getAttribute('data-testid'))+'"]':''),text:el.textContent.trim()};}
      else learned[kind]=descriptorFor(el,kind);unpick();setPanel(true);details.open=true;loopStatus.textContent='Selected '+kind+' indicator. '+(kind==='ready'?'Use a finished status or duration that appears only when a result is complete.':'');saveSetup();updateIndicators();}
      catch(err){unpick();setPanel(true);details.open=true;loopStatus.textContent=err.message;}
    };document.addEventListener('click',picker,true);document.addEventListener('keydown',escapePick,true);};
  createPick.onclick=()=>pick('create');busyPick.onclick=()=>pick('busy');readyPick.onclick=()=>pick('ready');
  start.onclick=()=>{try{
    if(!learned.create||!learned.busy||!learned.ready)throw new Error('Pick Create, a loading indicator, and a completed status or duration first.');
    if(learned.busy.selector===learned.ready.selector&&learned.busy.text===learned.ready.text)throw new Error('Loading and completed indicators must be different.');
    detach();route=location.pathname;sampleError='';saveSetup();runner.start({minDelay:min.field.value,maxDelay:max.field.value,limit:limit.field.value});lastTick=Date.now();
    observer=new MutationObserver(records=>{if(Date.now()-lastTick>60000){runner.stop('The browser was paused. Check the page and restart the loop.');return;}if(records.some(r=>r.target!==host&&!host.contains(r.target)))runner.tick();});observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,characterData:true});
    timer=setInterval(()=>{const t=Date.now();if(t-lastTick>60000){runner.stop('The browser was paused. Check the page and restart the loop.');return;}lastTick=t;runner.tick();},1000);
  }catch(e){loopStatus.textContent=e.message;}};
  stop.onclick=()=>runner.stop();reset.onclick=()=>{learned={create:null,busy:null,ready:null};createElement=null;saveSetup();updateIndicators();loopStatus.textContent='Page setup cleared.';};
  globalThis.VoodooStopLoop=()=>runner.stop('Paused. Restart the loop when ready.');
  window.addEventListener('pagehide',()=>runner.stop('Page closed. Loop stopped.'));
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!picker&&['waiting','awaiting','cooldown'].includes(runner.state)){runner.stop();setPanel(true);details.open=true;}});
  for(const input of [min.field,max.field,limit.field])input.addEventListener('change',saveSetup);
  ext.storage.local.get('workbenchLoopSetup').then(data=>{const s=data.workbenchLoopSetup;if(s){learned=s.learned||learned;min.field.value=s.min||'5';max.field.value=s.max||'15';limit.field.value=s.limit||'0';}updateIndicators();}).catch(()=>updateIndicators());
})();
