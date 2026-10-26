const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const base=path.join(__dirname,'../app/src/main/assets/script-ai');
test('model catalog pins correct optional Q4_K_M weights, SHA-256 and license metadata',()=>{
 const c=JSON.parse(fs.readFileSync(path.join(base,'models.json'),'utf8'));assert.equal(c.models.length,2);assert.equal(c.default,'qwen-coder-1.5b-q4');
 for(const m of c.models){assert.match(m.url,/https:\/\/huggingface.co\/Qwen\/Qwen2.5-Coder-(1.5B|3B)-Instruct-GGUF\/resolve\/[a-f0-9]{40}\/.*q4_k_m.gguf$/);assert.match(m.sha256,/^[a-f0-9]{64}$/);assert.ok(m.bytes>1000000000);assert.ok(m.licenseURL.startsWith('https://'));}
 assert.match(c.models[1].license,/Research/);
});
test('local reference covers core userscript topics and has versioned source links',()=>{
 const r=JSON.parse(fs.readFileSync(path.join(base,'reference.json'),'utf8'));assert.ok(r.version);for(const id of ['metadata','storage','http','lifecycle','dom','events','javascript','resources','debugging'])assert.ok(r.documents.some(d=>d.id===id&&d.source&&d.text));
});
