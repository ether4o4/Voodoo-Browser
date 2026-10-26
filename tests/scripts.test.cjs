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
test('rejects invalid metadata and reports unsupported privileged grants without preventing disabled editing',()=>{
 for(const script of ['alert(1)',code('https://example.com'),code('https://example.com/*','const =;')])assert.throws(()=>E.validate(script));
 assert.match(E.diagnostics(E.validate(code('https://example.com/*','','// @grant GM_cookie'))).join(),/Unsupported grant/);
 assert.equal(E.metadata(code('https://example.com/*','','// @require https://example.com/lib.js')).fields.require.length,1);
});
test('extracts a complete userscript, not ordinary fenced snippets or truncated output',()=>{
 const s=code('https://example.com/*');assert.equal(E.extractCode('```js\nconst x=1;\n```\n```javascript\n'+s+'\n```'),s);
 assert.equal(E.extractCode('```javascript\n'+s),null);assert.equal(E.extractCode('```js\nconsole.log(1)\n```'),null);
});
test('cleans known tracking fields without dropping application state',()=>{
 assert.equal(E.cleanUrl('https://example.com/p?utm_source=x&token=keep&fbclid=abc#part'),'https://example.com/p?token=keep#part');
});
function page(url='https://example.com/Create'){return new JSDOM('<body><p>Page</p></body>',{url,runScripts:'outside-only'});}
test('runtime injection source contains no page-context dynamic evaluator or localStorage',()=>{
 const source=fs.readFileSync(path.join(assets,'tools/runtime.js'),'utf8');
 assert.doesNotMatch(source,/\beval\s*\(|new Function|localStorage/);
 const native=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/ether4o4/voodoo/ScriptCoordinator.java'),'utf8');
 assert.match(native,/getExecutionWorld/);assert.match(native,/addWebMessageListener\(view,"VoodooGM",rules.origins\(\),world/);
 assert.match(native,/No privileged page-world fallback/);
});
test('include/exclude, noframes, modern grants and metadata are retained',()=>{
 const meta=E.metadata('// ==UserScript==\n// @name Example\n// @namespace org.example\n// @version 2\n// @include https://*.example.com/*\n// @exclude *private*\n// @noframes\n// @grant GM.xmlHttpRequest\n// @connect example.org\n// ==/UserScript==');
 assert.equal(meta.noframes,true);assert.equal(meta.version,'2');assert.equal(E.matches(meta,'https://a.example.com/path'),true);assert.equal(E.matches(meta,'https://a.example.com/private'),false);assert.equal(E.diagnostics(meta).length,0);
});
test('internal origins, credentials and trailing-dot internal origins remain excluded',()=>{
 const meta=E.metadata(code('<all_urls>'));
 for(const u of ['https://appassets.androidplatform.net/x','https://appassets.androidplatform.net./x','https://key@example.com/'])assert.equal(E.matches(meta,u),false);
});
test('reader uses text without copying scripts or HTML events',()=>{const d=page();d.window.document.body.innerHTML='<article><h1>Title</h1><p>Read me</p><script>window.bad=1</script></article>';Object.defineProperty(d.window.document.querySelector('article'),'innerText',{value:'Title\nRead me'});d.window.eval(fs.readFileSync(path.join(assets,'tools/reader.js'),'utf8'));assert.ok(d.window.document.getElementById('voodoo-reader'));assert.equal(d.window.bad,undefined);d.window.eval(fs.readFileSync(path.join(assets,'tools/reader.js'),'utf8'));assert.equal(d.window.document.getElementById('voodoo-reader'),null);d.window.close();});
