(function(root){
  'use strict';
  const groups=['cyan','pink','green','gold'],colors={cyan:'#8BBAC4',pink:'#CA96B5',green:'#ACC19C',gold:'#D2B580'},names={cyan:'Browse',pink:'Create',green:'Build',gold:'Saved'};
  function url(value){let u;try{u=new URL(value.trim());}catch{throw Error('Enter a full HTTPS website address.');}if(u.protocol!=='https:'||u.username||u.password||u.hostname==='appassets.androidplatform.net')throw Error('Pin an HTTPS website.');return u.href;}
  function validate(pins){if(!Array.isArray(pins)||pins.length>60)throw Error('A backup can contain up to 60 pins.');const ids=new Set();return pins.map(p=>{if(!p||typeof p.id!=='string'||!p.id||p.id.length>100||ids.has(p.id)||typeof p.title!=='string'||!p.title.trim()||p.title.length>120||!groups.includes(p.group)||!Number.isFinite(p.lat)||!Number.isFinite(p.lon)||Math.abs(p.lat)>85||Math.abs(p.lon)>180)throw Error('Invalid pin in backup.');ids.add(p.id);const clean={id:p.id,title:p.title,url:url(p.url),group:p.group,lat:p.lat,lon:p.lon};if(typeof p.icon==='string'&&p.icon.startsWith('data:image/png;base64,')&&p.icon.length<50000)clean.icon=p.icon;return clean;});}
  const api={groups,colors,names,url,validate};root.VoodooPins=api;if(typeof module==='object'&&module.exports)module.exports=api;
})(globalThis);
