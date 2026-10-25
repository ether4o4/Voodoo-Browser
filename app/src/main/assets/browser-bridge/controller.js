/* Runs only in a dedicated Android WebView execution world. No page-world fallback. */
(() => {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.hostname === 'appassets.androidplatform.net') return;
  const host = window.VoodooBrowserHost;
  if (!host) return;
  const documentId = crypto.randomUUID();
  let snapshotId = '', serial = 0, refs = new Map();
  const selector = 'button,a[href],input,textarea,select,[role=button],[role=link],[role=checkbox],[role=menuitem],[contenteditable=true]';
  const sensitive = e => e.matches('input[type=password],input[type=hidden],input[type=file]') || /password|one-time-code|cc-/i.test(e.autocomplete || '') || !!e.closest('[data-voodoo-private]');
  const visible = e => {const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&!e.closest('[hidden],[inert],[aria-hidden=true]');};
  const name = e => (e.getAttribute('aria-label') || (e.labels && [...e.labels].map(x=>x.textContent).join(' ')) || e.innerText || e.textContent || e.getAttribute('placeholder') || e.getAttribute('name') || '').trim().slice(0,200);
  const fingerprint = e => {
    const row=e.closest('[role=row],tr,li,article,[data-song-id],[data-id]');
    const attributes=[...e.attributes].filter(a=>/^(data-|aria-|id$|href$|action$|formaction$|name$|type$)/.test(a.name)).map(a=>[a.name,a.value]);
    return JSON.stringify([e.tagName,e.type,name(e),attributes,e.disabled,e.checked,e.value,e.selectedIndex,row?.getAttribute('data-id'),row?.getAttribute('data-song-id'),row?.textContent.slice(0,1200)]);
  };
  const safeUrl = (url, allowed) => {const u=new URL(url,location.href);if(u.protocol!=='https:'||u.username||u.password||!allowed.includes(u.origin))throw Error('Destination website is not allowed.');return u;};
  const pageText = () => {
    const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);let out='',n;
    while((n=walker.nextNode())&&out.length<20000){const e=n.parentElement;if(!e||e.closest('script,style,noscript,input,textarea,select,[contenteditable],[data-voodoo-private]')||!visible(e))continue;out+=n.textContent+' ';}
    return out.slice(0,20000).replace(/\s+/g,' ').trim();
  };
  const read = () => {
    snapshotId=documentId+':'+(++serial);refs=new Map();
    const elements=[...document.querySelectorAll(selector)].filter(e=>visible(e)&&!sensitive(e)).slice(0,300).map((e,i)=>{
      const ref='e'+i,r=e.getBoundingClientRect();refs.set(ref,{node:e,fingerprint:fingerprint(e)});
      return {ref,role:e.getAttribute('role')||e.tagName.toLowerCase(),name:name(e),enabled:!e.disabled&&e.getAttribute('aria-disabled')!=='true',selected:e.selected||e.getAttribute('aria-selected')==='true',checked:e.checked||e.getAttribute('aria-checked')==='true',x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)};
    });
    return {documentId,snapshotId,text:pageText(),elements,frames:[...document.querySelectorAll('iframe')].map(e=>({title:e.title||'',supported:false})),limits:{maxElements:300,maxText:20000,frames:'Main frame only'}};
  };
  const target = c => {
    if(c.snapshotId!==snapshotId)throw Error('Stale snapshot; read the page again.');
    const entry=refs.get(c.ref),e=entry?.node;
    if(!e||!e.isConnected||fingerprint(e)!==entry.fingerprint||!visible(e)||sensitive(e))throw Error('Stale or protected element; read the page again.');
    if(e.disabled||e.getAttribute('aria-disabled')==='true')throw Error('Element is disabled.');
    return e;
  };
  const execute = c => {
    if(c.documentId!==documentId)throw Error('Document changed; read the page again.');
    if(c.deadline<=Date.now())throw Error('Command expired.');
    if(c.op==='read_page')return read();
    if(c.op==='wait_for')return {matched:pageText().includes(c.text)};
    if(c.op==='scroll'){
      let e=c.ref?target(c):document.scrollingElement;
      if(c.ref){while(e&&e.scrollHeight<=e.clientHeight)e=e.parentElement;if(!e)e=document.scrollingElement;}
      const before=e.scrollTop;e.scrollTop+=c.pixels;return {observed:true,scrollTop:e.scrollTop,moved:e.scrollTop!==before};
    }
    const e=target(c);
    if(c.op==='click'){
      const link=e.closest('a[href]');if(link)safeUrl(link.href,c.allowedOrigins);
      if(e.form&&(e.type==='submit'||e.tagName==='BUTTON'))safeUrl(e.getAttribute('formaction')||e.form.action||location.href,c.allowedOrigins);
      e.click();snapshotId='';refs.clear();
      return {observed:true,eventDispatched:true,applicationOutcome:'Verify the resulting page; a click does not prove the website saved a change.'};
    }
    if(c.op==='type'){
      if(!e.matches('input,textarea,[contenteditable=true]')||e.readOnly)throw Error('Element is not an editable text field.');
      if(e.matches('input')&&!['text','search','email','tel','url','number'].includes(e.type))throw Error('Input type is not supported.');
      e.focus();
      if(e.isContentEditable)e.textContent=c.text;
      else {const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(e,c.text);}
      e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
      const verified=(e.isContentEditable?e.textContent:e.value)===c.text;snapshotId='';refs.clear();
      if(!verified)throw Error('The page did not retain the entered text.');return {observed:true,valueMatches:true};
    }
    if(c.op==='select'){
      if(e.tagName!=='SELECT'||![...e.options].some(x=>x.value===c.value&&!x.disabled))throw Error('Option is unavailable.');
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(e,c.value);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
      snapshotId='';refs.clear();if(e.value!==c.value)throw Error('The page did not retain the selection.');return {observed:true,selectionMatches:true};
    }
    throw Error('Unsupported page operation.');
  };
  host.onmessage = event => {
    let c;
    try{c=JSON.parse(event.data);const data=execute(c);host.postMessage(JSON.stringify({type:'result',id:c.id,documentId,ok:true,data}));}
    catch(e){host.postMessage(JSON.stringify({type:'result',id:c?.id,documentId,ok:false,error:String(e.message||e)}));}
  };
  host.postMessage(JSON.stringify({type:'ready',documentId}));
})();
