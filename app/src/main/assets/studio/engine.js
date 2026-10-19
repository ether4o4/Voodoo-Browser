(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.VoodooEngine=api;})(globalThis,function(){
  'use strict';
  const blocked='appassets.androidplatform.net';
  function metadata(code){
    const header=code.match(/\/\/\s*==UserScript==([\s\S]*?)\/\/\s*==\/UserScript==/);
    if(!header)throw Error('Add a userscript header with @name and @match.');
    const fields={};for(const m of header[1].matchAll(/^\s*\/\/\s*@([\w-]+)\s+(.+)$/gm))(fields[m[1]]??=[]).push(m[2].trim());
    const matches=fields.match||[];if(!matches.length)throw Error('Add at least one @match URL pattern.');
    for(const p of [...matches,...(fields['exclude-match']||[])])pattern(p);
    const unsupported=['require','resource','connect','include','exclude'];for(const key of unsupported)if(fields[key])throw Error('@'+key+' is not supported. Use @match / @exclude-match and self-contained code.');
    const allowed=['none','GM_addStyle','GM_getValue','GM_setValue','GM_deleteValue'];for(const g of fields.grant||[])if(!allowed.includes(g))throw Error('Unsupported grant: '+g+'. This runner supports DOM scripts and local GM value/style helpers.');
    const runAt=fields['run-at']?.[0]||'document-idle';if(!['document-start','document-end','document-idle'].includes(runAt))throw Error('Use document-start, document-end, or document-idle.');
    return {name:fields.name?.[0]||'Untitled script',matches,excludes:fields['exclude-match']||[],runAt};
  }
  function pattern(value){
    if(value==='<all_urls>')return /^https?:\/\//;
    const m=value.match(/^(https?|\*):\/\/(\*|\*\.[A-Za-z0-9.-]+|[A-Za-z0-9.-]+|localhost)(\/[^\s]*)$/);
    if(!m)throw Error('Invalid @match: '+value);
    const esc=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
    const scheme=m[1]==='*'?'https?':m[1],host=m[2]==='*'?'[^/]+':m[2].startsWith('*.')?'(?:[^/]+\\.)?'+esc(m[2].slice(2).toLowerCase()):esc(m[2].toLowerCase());
    return new RegExp('^'+scheme+'://'+host+'(?::[0-9]+)?'+m[3].split('*').map(esc).join('.*')+'$');
  }
  function matches(meta,url){try{const u=new URL(url);if(!['https:','http:'].includes(u.protocol)||u.hostname===blocked)return false;const target=u.href.split('#')[0];return meta.matches.some(p=>pattern(p).test(target))&&!meta.excludes.some(p=>pattern(p).test(target));}catch{return false;}}
  function validate(code){if(typeof code!=='string'||code.length>200000)throw Error('Scripts must be under 200 KB.');const meta=metadata(code);new Function(code);return meta;}
  function extractCode(text){const all=[...text.matchAll(/```(?:javascript|js|userscript)?\s*\n([\s\S]*?)```/g)];return all.find(m=>m[1].includes('==UserScript=='))?.[1]?.trim()||null;}
  function cleanUrl(input){try{const u=new URL(input);for(const k of [...u.searchParams.keys()])if(/^(utm_.+|fbclid|gclid|dclid|msclkid|mc_cid|mc_eid)$/i.test(k))u.searchParams.delete(k);return u.href;}catch{return input;}}
  return {metadata,pattern,matches,validate,extractCode,cleanUrl};
});
