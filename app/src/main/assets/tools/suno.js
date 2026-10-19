(function(){
  'use strict';
  if(document.getElementById('fourgauteau-host'))return;
  const ext=globalThis.VoodooSunoAPI;
  if(!ext?.runtime?.id)return;
  const KEY='fourgauteau.workbench.v1';
  const E=(tag,text)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;return e;};
  const host=E('div');host.id='fourgauteau-host';host.style.cssText='all:initial;position:fixed;right:16px;bottom:20px;z-index:2147483646;font-family:system-ui,sans-serif;';
  const shadow=host.attachShadow({mode:'open'}),style=E('style');
  style.textContent=':host{all:initial}*{box-sizing:border-box}button,select{font:inherit}button{cursor:pointer;min-height:42px;border-radius:7px;border:1px solid #ccd0c2;padding:9px 12px;background:#fffef9;color:#242723;font-size:12px}button:focus-visible,select:focus-visible{outline:3px solid #b44525;outline-offset:2px}.launcher{background:#242723;color:#f4f3ed;border-color:#69745e;font-weight:750;font-size:14px;letter-spacing:.7px;box-shadow:0 3px 16px #0002}.panel{background:#f4f3ed;color:#242723;padding:18px;border:1px solid #ccd0c2;border-radius:12px;box-shadow:0 6px 26px #0003;width:min(330px,calc(100vw - 32px));margin-bottom:10px;font:13px/1.6 system-ui,sans-serif;max-height:72dvh;overflow:auto}.top{display:flex;justify-content:space-between;align-items:center}.top strong{letter-spacing:1px;font-size:13px}.top button{border:0;background:transparent;font-size:20px;padding:0;width:32px;min-height:32px}p{font-size:12px;color:#65705b;margin:8px 0 12px}select{width:100%;padding:10px;border:1px solid #ccd0c2;border-radius:7px;background:#fffef9;color:#242723;font-size:14px}.fill{background:#242723;color:#f4f3ed;width:100%;margin:12px 0 5px}.row{display:flex;gap:6px;margin:8px 0}.row button{flex:1;font-size:10px;padding:7px 5px}.status{font-size:12px;white-space:pre-wrap;overflow-wrap:anywhere;color:#91391f;margin:10px 0}.open{width:100%}.foot{font-size:10px;color:#65705b;margin-bottom:0}[hidden]{display:none!important}';
  shadow.append(style);const panel=E('section');panel.className='panel';panel.hidden=true;panel.setAttribute('aria-label','4GAuteau Suno tools');const top=E('div');top.className='top';top.append(E('strong','4GAUTEAU / SUNO'));const close=E('button','×');close.setAttribute('aria-label','Close 4GAuteau tools');top.append(close);panel.append(top,E('p','Send a draft from Workbench or Tools → Fill from Workbench. Review before creating.'));
  const select=E('select');select.setAttribute('aria-label','Saved session');panel.append(select);const fill=E('button','Fill lyrics + style');fill.className='fill';panel.append(fill);const status=E('p');status.className='status';status.setAttribute('role','status');panel.append(status);
  const row=E('div');row.className='row';const pickLyrics=E('button','Pick lyrics field'),pickStyle=E('button','Pick style field'),pickExclude=E('button','Pick exclusions');row.append(pickLyrics,pickStyle,pickExclude);panel.append(row);const open=E('button','Open Workbench ↗');open.className='open';panel.append(open);const foot=E('p','Only the fields you choose are filled. Generation stays in your hands.');foot.className='foot';panel.append(foot);
  const launcher=E('button','4GA');launcher.className='launcher';launcher.setAttribute('aria-label','Open 4GAuteau Suno tools');launcher.setAttribute('aria-expanded','false');shadow.append(panel,launcher);document.documentElement.append(host);
  let songs=[],picked={},picking=null;
  function setPanel(show){panel.hidden=!show;launcher.setAttribute('aria-expanded',String(show));}
  async function loadSongs(){const data=(await ext.storage.local.get(KEY))[KEY];songs=Array.isArray(data?.songs)?data.songs:[];const previous=select.value;select.replaceChildren();if(!songs.length){const option=E('option','No saved sessions yet');option.value='';select.append(option);}songs.forEach(s=>{const option=E('option',s.title||'Untitled session');option.value=s.id;select.append(option);});select.value=songs.some(s=>s.id===previous)?previous:(data?.activeId||songs[0]?.id||'');fill.disabled=!songs.length;}
  function visible(e){const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&!e.disabled&&!e.readOnly&&getComputedStyle(e).visibility!=='hidden'&&getComputedStyle(e).display!=='none'&&!e.closest('[aria-hidden="true"]');}
  function fieldText(el){const labelled=(el.getAttribute('aria-labelledby')||'').split(/\s+/).filter(Boolean).map(id=>document.getElementById(id)?.textContent||'').join(' ');const labels=[...(el.labels||[])].map(l=>l.textContent).join(' ');return [el.getAttribute('aria-label'),el.getAttribute('placeholder'),el.getAttribute('name'),el.getAttribute('data-testid'),el.id,labelled,labels].filter(Boolean).join(' ').toLowerCase();}
  function findField(kind){
    if(picked[kind]?.isConnected&&visible(picked[kind]))return picked[kind];
    const all=[...document.querySelectorAll('textarea,input:not([type]),input[type="text"],[contenteditable="true"],[role="textbox"]')].filter(e=>visible(e)&&!host.contains(e));
    const scored=all.map(el=>{const text=fieldText(el);let score=0;
      if(kind==='lyrics'&&/lyrics?|song words|enter your words/.test(text))score+=10;
      if(kind==='style'&&/style|genre|music description|describe.*sound/.test(text))score+=10;
      if(kind==='exclude'&&/exclude|negative|avoid.*style/.test(text))score+=15;
      if(kind!=='exclude'&&/exclude|negative|avoid/.test(text))score-=30;
      if(kind==='lyrics'&&/style|genre|description/.test(text))score-=15;
      if(kind==='style'&&/lyrics?/.test(text))score-=15;
      if(kind==='style'&&/describe.*song|song description/.test(text))score-=20;
      return {el,score};}).filter(x=>x.score>0).sort((a,b)=>b.score-a.score);
    if(!scored.length)return null;
    if(scored.length>1&&scored[0].score===scored[1].score)return null;
    return scored[0].el;
  }
  function setField(el,text){
    el.focus();
    if(el instanceof HTMLTextAreaElement||el instanceof HTMLInputElement){const proto=el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const setter=Object.getOwnPropertyDescriptor(proto,'value')?.set;if(setter)setter.call(el,text);else el.value=text;}
    else if(el.isContentEditable){el.textContent=text;}
    else throw new Error('Choose an editable text field.');
    el.dispatchEvent(new Event('input',{bubbles:true,composed:true}));el.dispatchEvent(new Event('change',{bubbles:true,composed:true}));el.blur();
  }
  function getValue(el){return 'value' in el?el.value:el.textContent;}
  async function fillSong(s){
    setPanel(true);if(!s||(!s.lyrics?.trim()&&!s.style?.trim()))return {ok:false,error:'Add lyrics or a style prompt in Workbench first.'};
    const actions=[];
    for(const kind of ['lyrics','style','exclude']){const value=s[kind];if(typeof value!=='string'||!value.trim())continue;const el=findField(kind);if(!el){const error='Could not identify the '+kind+' field. Use Custom mode and “Pick '+(kind==='lyrics'?'lyrics':kind==='style'?'style':'exclusions')+' field” below.';status.textContent=error;return {ok:false,error};}if(el.maxLength>0&&value.length>el.maxLength){const error='Your '+kind+' has '+value.length+' characters; this field accepts '+el.maxLength+'. Shorten it before filling.';status.textContent=error;return {ok:false,error};}actions.push({kind,value,el});}
    if(new Set(actions.map(a=>a.el)).size!==actions.length){const error='Two fields resolve to the same input. Pick each field separately.';status.textContent=error;return {ok:false,error};}
    // Validate every target first, then change fields. Never press a generation button.
    const previous=actions.map(a=>getValue(a.el));
    const overwrites=actions.filter(a=>getValue(a.el).trim()&&getValue(a.el)!==a.value);
    if(overwrites.length&&!confirm('Replace the existing '+overwrites.map(a=>a.kind).join(', ')+' text on this Suno page with “'+(s.title||'your session')+'”?')){status.textContent='Kept the existing page text.';return {ok:false,error:'Fill cancelled. The existing text was kept.'};}
    try{actions.forEach(a=>setField(a.el,a.value));await new Promise(r=>setTimeout(r,160));if(actions.some(a=>getValue(a.el)!==a.value))throw new Error('Suno did not retain a field update. Copy the text from Workbench instead.');}
    catch(e){actions.forEach((a,i)=>{try{setField(a.el,previous[i]);}catch{}});status.textContent=e.message;return {ok:false,error:e.message};}
    status.textContent='Filled '+actions.map(a=>a.kind).join(', ')+'. Review your draft before generating.';return {ok:true,message:status.textContent};
  }
  function cancelPick(){if(picking){document.removeEventListener('click',picking,true);document.removeEventListener('keydown',escapePick,true);picking=null;}document.documentElement.style.cursor='';}
  function escapePick(e){if(e.key==='Escape'){cancelPick();setPanel(true);status.textContent='Field selection cancelled.';}}
  function pick(kind){cancelPick();status.textContent='Tap the '+kind+' input on the Suno page. Escape cancels.';panel.hidden=true;document.documentElement.style.cursor='crosshair';picking=e=>{if(e.composedPath().includes(host))return;const el=e.target.closest?.('textarea,input,[contenteditable="true"],[role="textbox"]');if(!el||!visible(el))return;e.preventDefault();e.stopImmediatePropagation();picked[kind]=el;cancelPick();setPanel(true);status.textContent='Selected '+kind+' field. Tap Fill lyrics + style when ready.';};document.addEventListener('click',picking,true);document.addEventListener('keydown',escapePick,true);}
  launcher.onclick=async()=>{cancelPick();if(panel.hidden){await loadSongs();setPanel(true);}else setPanel(false);};close.onclick=()=>{cancelPick();setPanel(false);};open.onclick=()=>ext.runtime.sendMessage({type:'open-workbench'});fill.onclick=async()=>{await loadSongs();const result=await fillSong(songs.find(s=>s.id===select.value));if(!result.ok)status.textContent=result.error;};pickLyrics.onclick=()=>pick('lyrics');pickStyle.onclick=()=>pick('style');pickExclude.onclick=()=>pick('exclude');
  ext.runtime.onMessage.addListener((message,sender,respond)=>{if(sender.id!==ext.runtime.id||message.type!=='workbench-fill')return false;fillSong(message.song).then(respond,e=>respond({ok:false,error:e.message}));return true;});
  globalThis.WorkbenchSunoTools={ext,host,shadow,panel,visible,E,setPanel};
})();
