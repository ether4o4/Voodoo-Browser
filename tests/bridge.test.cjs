const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{JSDOM}=require('jsdom');
test('native bridge routes streaming without resolving early and provides scoped cancellation',async()=>{
 const d=new JSDOM('<body></body>',{url:'https://appassets.androidplatform.net/',runScripts:'outside-only'}),w=d.window,requests=[];
 w.VoodooHost={postMessage:r=>requests.push(JSON.parse(r))};w.eval(fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/studio/bridge.js'),'utf8'));
 try{let delta='',complete=false;const p=w.Host.call('chat',{profileId:'openai'},t=>delta+=t);p.then(()=>complete=true);
  w.VoodooHost.onmessage({data:JSON.stringify({id:requests[0].id,event:'delta',text:'hello'})});await Promise.resolve();assert.equal(delta,'hello');assert.equal(complete,false);
  const cancellation=p.cancel();assert.equal(requests[1].op,'cancel-chat');assert.equal(requests[1].requestId,requests[0].id);
  w.VoodooHost.onmessage({data:JSON.stringify({id:requests[1].id,ok:true,data:true})});await cancellation;
  w.VoodooHost.onmessage({data:JSON.stringify({id:requests[0].id,ok:true,data:{text:'hello'}})});assert.equal((await p).text,'hello');
 }finally{w.close();}
});
