const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{JSDOM}=require('jsdom');
const source=fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/browser-bridge/controller.js'),'utf8');
function setup(t,html){
 const dom=new JSDOM(html,{url:'https://example.com/page',runScripts:'outside-only'}),w=dom.window,messages=[];
 t.after(()=>w.close());
 w.HTMLElement.prototype.getBoundingClientRect=function(){return {x:0,y:0,width:100,height:30};};
 Object.defineProperty(w.document,'scrollingElement',{value:w.document.documentElement});
 w.VoodooBrowserHost={postMessage:text=>messages.push(JSON.parse(text))};w.eval(source);
 const documentId=messages.shift().documentId;
 const send=(op,args={})=>{w.VoodooBrowserHost.onmessage({data:JSON.stringify({id:'command-123',documentId,deadline:Date.now()+10000,allowedOrigins:['https://example.com'],op,...args})});return messages.pop();};
 const read=()=>send('read_page').data;
 const reference=(snapshot,index=0)=>({snapshotId:snapshot.snapshotId,ref:snapshot.elements[index].ref});
 return {w,send,read,reference};
}
test('read excludes private fields and text while retaining useful controls',t=>{
 const f=setup(t,'<body><p>Hello there</p><button>Save</button><input type=password value=secret><input autocomplete=cc-number value=4111><div data-voodoo-private>Hidden secret</div><textarea>Draft secret</textarea><script>secret</script>');
 const s=f.read();assert.equal(s.text,'Hello there Save');assert.equal(s.elements.length,2);assert.equal(s.elements[0].name,'Save');assert.ok(!JSON.stringify(s).includes('Hidden secret'));assert.ok(!JSON.stringify(s).includes('4111'));
});
test('element references retain node identity when other elements are inserted',t=>{
 const f=setup(t,'<body><button id=save>Save</button>'),s=f.read();let saved=0,wrong=0;
 f.w.document.querySelector('#save').onclick=()=>saved++;const b=f.w.document.createElement('button');b.textContent='Delete';b.onclick=()=>wrong++;f.w.document.body.prepend(b);
 assert.equal(f.send('click',f.reference(s)).ok,true);assert.equal(saved,1);assert.equal(wrong,0);
});
test('removed, recycled, and old snapshot references are rejected',t=>{
 const f=setup(t,'<body><button>Save</button>');let s=f.read();f.w.document.querySelector('button').textContent='Delete';assert.equal(f.send('click',f.reference(s)).ok,false);
 s=f.read();f.w.document.querySelector('button').remove();assert.equal(f.send('click',f.reference(s)).ok,false);
 f.w.document.body.innerHTML='<button>Save</button>';s=f.read();f.read();assert.equal(f.send('click',f.reference(s)).ok,false);
});
test('scroll works without an element reference and reports the observed position',t=>{
 const f=setup(t,'<body><p>Page</p>');const r=f.send('scroll',{pixels:600});assert.equal(r.ok,true);assert.equal(r.data.scrollTop,600);assert.equal(r.data.moved,true);
});
test('typing fires events, verifies retained text and invalidates old references',t=>{
 const f=setup(t,'<body><input placeholder=Name>'),s=f.read();let events=0;f.w.document.querySelector('input').addEventListener('input',()=>events++);
 assert.equal(f.send('type',{...f.reference(s),text:'Voodoo'}).ok,true);assert.equal(f.w.document.querySelector('input').value,'Voodoo');assert.equal(events,1);assert.equal(f.send('type',{...f.reference(s),text:'Again'}).ok,false);
});
test('password changes and rejected input updates do not report success',t=>{
 const f=setup(t,'<body><input type=text>');let s=f.read();const e=f.w.document.querySelector('input');e.type='password';assert.equal(f.send('type',{...f.reference(s),text:'secret'}).ok,false);assert.equal(e.value,'');
 e.type='text';s=f.read();e.oninput=()=>{e.value='rejected';};assert.equal(f.send('type',{...f.reference(s),text:'requested'}).ok,false);
});
test('click blocks disallowed link destinations and describes dispatch honestly',t=>{
 const f=setup(t,'<body><a href="https://other.example/">Leave</a><button>Save</button>'),s=f.read();
 assert.equal(f.send('click',f.reference(s)).ok,false);const result=f.send('click',f.reference(s,1));assert.equal(result.ok,true);assert.equal(result.data.eventDispatched,true);assert.match(result.data.applicationOutcome,/Verify/);
});
test('selection and waits verify actual state, expiry and document identity',t=>{
 const f=setup(t,'<body><p>Loading</p><select><option value=a>A</option><option value=b>B</option><option value=c disabled>C</option></select>');let s=f.read();
 assert.equal(f.send('select',{...f.reference(s),value:'c'}).ok,false);assert.equal(f.send('select',{...f.reference(s),value:'b'}).ok,true);assert.equal(f.w.document.querySelector('select').value,'b');
 assert.equal(f.send('wait_for',{text:'Finished'}).data.matched,false);f.w.document.querySelector('p').textContent='Finished';assert.equal(f.send('wait_for',{text:'Finished'}).data.matched,true);
 assert.equal(f.send('read_page',{deadline:Date.now()-1}).ok,false);assert.equal(f.send('read_page',{documentId:'wrong-document'}).ok,false);
});

test('reused row controls and changed checkbox state require a fresh snapshot',t=>{
 const f=setup(t,'<body><div role=row><span>Song A</span><button>More</button></div><input type=checkbox>');let s=f.read();
 f.w.document.querySelector('span').textContent='Song B';assert.equal(f.send('click',f.reference(s)).ok,false);
 s=f.read();f.w.document.querySelector('input').checked=true;assert.equal(f.send('click',f.reference(s,1)).ok,false);
});
