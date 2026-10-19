document.addEventListener('DOMContentLoaded',async()=>{
  const $=id=>document.getElementById(id),status=e=>$('status').textContent=e.message;
  $('search-form').onsubmit=e=>{e.preventDefault();Host.call('navigate',{text:$('search').value}).catch(status);};
  document.querySelectorAll('[data-url]').forEach(b=>b.onclick=()=>Host.call('open-url',{url:b.dataset.url}).catch(status));
  $('ai').onclick=()=>Host.call('open-studio').catch(status);$('write').onclick=()=>Host.call('open-workbench').catch(status);
  try{const options=await Host.call('get-store',{key:'options'});if(options?.homeImage?.startsWith('data:image/')){$('home-image').src=options.homeImage;$('home-art').hidden=false;$('home-mark').hidden=true;}}catch{}
});
