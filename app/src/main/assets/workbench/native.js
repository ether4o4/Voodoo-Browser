(function(){
  const api={runtime:{id:'voodoo-workbench',sendMessage:message=>Host.call(message.type==='fill-suno'?'fill-suno':'open-workbench',message)},storage:{local:{async get(key){return {[key]:await Host.call('get-store',{key})};},async set(values){for(const [key,value] of Object.entries(values))await Host.call('put-store',{key,value});}}},tabs:{create:({url})=>Host.call('open-url',{url})}};
  globalThis.browser=api;
  globalThis.VoodooDownload=(filename,text,type)=>Host.call('export-file',{filename,text,mime:type});
  document.addEventListener('DOMContentLoaded',()=>{const nav=document.querySelector('nav')||document.querySelector('header');if(!nav)return;const button=document.createElement('button');button.className='secondary';button.textContent='Script Hub';button.onclick=()=>Host.call('open-script-hub').catch(e=>alert(e.message));nav.append(button);});
})();
