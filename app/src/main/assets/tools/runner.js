(function(){
  if(globalThis.VoodooScripts)return;
  const ran=new Set();
  function run(script,manual=false){
    try{
      if(window.top!==window||location.hostname==='appassets.androidplatform.net')return 'Internal pages are excluded.';
      const meta=VoodooEngine.metadata(script.code);if(!VoodooEngine.matches(meta,location.href))return 'This URL does not match the script.';
      if(!manual&&ran.has(script.id))return 'Already ran.';
      ran.add(script.id);const prefix='voodoo.script.'+script.id+'.';
      const style=css=>{const e=document.createElement('style');e.textContent=css;(document.head||document.documentElement).append(e);return e;};
      const get=(key,fallback)=>{try{const v=localStorage.getItem(prefix+key);return v===null?fallback:JSON.parse(v);}catch{return fallback;}};
      const set=(key,v)=>localStorage.setItem(prefix+key,JSON.stringify(v));
      const del=key=>localStorage.removeItem(prefix+key);
      new Function('GM_addStyle','GM_getValue','GM_setValue','GM_deleteValue',script.code)(style,get,set,del);
      return 'Ran '+meta.name;
    }catch(e){console.error('[Voodoo script]',e.message);return 'Script error: '+e.message;}
  }
  function runAll(scripts){
    for(const script of scripts){if(!script.enabled)continue;let meta;try{meta=VoodooEngine.metadata(script.code);}catch{continue;}
      if(!VoodooEngine.matches(meta,location.href))continue;
      if(meta.runAt==='document-start')run(script);
      else if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>meta.runAt==='document-idle'?setTimeout(()=>run(script),250):run(script),{once:true});
      else meta.runAt==='document-idle'?setTimeout(()=>run(script),250):run(script);
    }
  }
  globalThis.VoodooScripts={run,runAll};
})();
