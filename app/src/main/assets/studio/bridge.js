(function(){
  'use strict';let id=0;const pending=new Map();
  if(!globalThis.VoodooHost){document.addEventListener('DOMContentLoaded',()=>{const el=document.getElementById('status');if(el)el.textContent='Open this workspace inside Voodoo Browser.';});return;}
  VoodooHost.onmessage=event=>{let r;try{r=JSON.parse(event.data);}catch{return;}const p=pending.get(r.id);if(!p)return;pending.delete(r.id);clearTimeout(p.timer);r.ok?p.resolve(r.data):p.reject(Error(r.error||'Request failed.'));};
  globalThis.Host={call(op,args={}){return new Promise((resolve,reject)=>{const token=String(++id);const timer=setTimeout(()=>{pending.delete(token);reject(Error('Request timed out.'));},150000);pending.set(token,{resolve,reject,timer});VoodooHost.postMessage(JSON.stringify({id:token,op,...args}));});}};
})();
