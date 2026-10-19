const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {JSDOM}=require('jsdom');const assets=path.join(__dirname,'../app/src/main/assets');const E=require(path.join(assets,'studio/engine.js'));
const code=(match,body='window.runs=(window.runs||0)+1;',extra='')=>`// ==UserScript==\n// @name Test\n// @match ${match}\n// @grant none\n${extra}\n// ==/UserScript==\n${body}`;
test('host boundaries, schemes, paths, exclusions and internal pages',()=>{
 const m=E.metadata(code('https://*.example.com/Create*','','// @exclude-match https://private.example.com/*'));
 for(const u of ['https://example.com/Create','https://a.example.com/Create?q=x#hash'])assert.equal(E.matches(m,u),true,u);
 for(const u of ['https://example.com/create','https://example.com.evil/Create','https://private.example.com/Create','http://example.com/Create','https://evil.test/?https://example.com/Create'])assert.equal(E.matches(m,u),false,u);
 assert.equal(E.matches(E.metadata(code('<all_urls>')),'https://appassets.androidplatform.net/assets/home.html'),false);
 assert.equal(E.matches(E.metadata(code('<all_urls>')),'file:///secret'),false);
});
test('rejects invalid metadata and unsupported privileged grants',()=>{
 for(const script of ['alert(1)',code('https://example.com'),code('https://example.com/*','','// @require https://evil.test/a.js'),code('https://example.com/*','','// @grant GM_xmlhttpRequest'),code('https://example.com/*','const =;')])assert.throws(()=>E.validate(script));
});
test('extracts a complete userscript, not ordinary fenced snippets or truncated output',()=>{
 const s=code('https://example.com/*');assert.equal(E.extractCode('```js\nconst x=1;\n```\n```javascript\n'+s+'\n```'),s);
 assert.equal(E.extractCode('```javascript\n'+s),null);assert.equal(E.extractCode('```js\nconsole.log(1)\n```'),null);
});
test('cleans known tracking fields without dropping application state',()=>{
 assert.equal(E.cleanUrl('https://example.com/p?utm_source=x&token=keep&fbclid=abc#part'),'https://example.com/p?token=keep#part');
});
function page(url='https://example.com/Create'){const dom=new JSDOM('<body><p>Page</p></body>',{url,runScripts:'outside-only'});dom.window.eval(fs.readFileSync(path.join(assets,'studio/engine.js'),'utf8'));dom.window.eval(fs.readFileSync(path.join(assets,'tools/runner.js'),'utf8'));return dom;}
test('automatic runs deduplicate; manual run is explicit; disabled and unmatched scripts do not run',()=>{
 const d=page();const w=d.window;const s={id:'1',code:code('https://example.com/*','','// @run-at document-start'),enabled:true};s.code=code('https://example.com/*','window.runs=(window.runs||0)+1;','// @run-at document-start');
 w.VoodooScripts.runAll([s,{...s,id:'off',enabled:false},{...s,id:'other',code:code('https://elsewhere.test/*')}]);w.VoodooScripts.runAll([s]);assert.equal(w.runs,1);w.VoodooScripts.run(s,true);assert.equal(w.runs,2);d.window.close();
});
test('GM helpers isolate script keys and add style; errors are returned',()=>{
 const d=page();const s={id:'a',code:code('https://example.com/*',"GM_setValue('x',7);window.value=GM_getValue('x');GM_addStyle('body{color:red}');GM_deleteValue('x');")};assert.equal(d.window.VoodooScripts.run(s,true),'Ran Test');assert.equal(d.window.value,7);assert.equal(d.window.localStorage.getItem('voodoo.script.a.x'),null);assert.equal(d.window.document.querySelectorAll('style').length,1);
 assert.match(d.window.VoodooScripts.run({id:'b',code:code('https://example.com/*',"throw Error('fixture error')")},true),/fixture error/);d.window.close();
});
test('runner refuses internal workspace even for all URLs',()=>{const d=page('https://appassets.androidplatform.net/assets/studio/index.html');assert.match(d.window.VoodooScripts.run({id:'x',code:code('<all_urls>')},true),/excluded/);assert.equal(d.window.runs,undefined);d.window.close();});
test('reader uses text without copying scripts or HTML events',()=>{const d=page();d.window.document.body.innerHTML='<article><h1>Title</h1><p>Read me</p><script>window.bad=1</script></article>';Object.defineProperty(d.window.document.querySelector('article'),'innerText',{value:'Title\nRead me'});d.window.eval(fs.readFileSync(path.join(assets,'tools/reader.js'),'utf8'));assert.ok(d.window.document.getElementById('voodoo-reader'));assert.equal(d.window.bad,undefined);d.window.eval(fs.readFileSync(path.join(assets,'tools/reader.js'),'utf8'));assert.equal(d.window.document.getElementById('voodoo-reader'),null);d.window.close();});
