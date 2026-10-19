(function(){
  const previous=document.getElementById('voodoo-reader');if(previous){previous.remove();return;}
  const main=document.querySelector('article')||document.querySelector('main')||document.body;
  const text=main.innerText;if(!text?.trim()){alert('No readable text found.');return;}
  const host=document.createElement('div');host.id='voodoo-reader';host.style.cssText='position:fixed;inset:0;z-index:2147483647;overflow:auto;background:#f4f3ed;color:#242723';
  const root=host.attachShadow({mode:'open'}),style=document.createElement('style');style.textContent='article{font:19px/1.8 Georgia,serif;max-width:680px;margin:auto;padding:28px;white-space:pre-wrap;overflow-wrap:anywhere}button{position:sticky;top:10px;float:right;margin:10px 20px;padding:12px 20px;background:#242723;color:#fff;border:0;border-radius:8px;font:15px system-ui}';
  const close=document.createElement('button');close.textContent='Close reader';close.onclick=()=>host.remove();const article=document.createElement('article');article.textContent=document.title+'\n\n'+text.slice(0,200000);root.append(style,close,article);document.documentElement.append(host);
})();
