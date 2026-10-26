import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { createBridge } from '../src/server.mjs';

async function fixture(t, extra = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'voodoo-relay-'));
  const config = { publicUrl: 'http://127.0.0.1', testOnlyAllowHttp: true, adminSecret: 'admin-'.repeat(8), ownerSecret: 'owner-'.repeat(8), clientId: 'test-client', clientSecret: 'client-'.repeat(8), redirectUris: ['https://client.example/callback'], stateFile: path.join(dir, 'state.json'), ...extra };
  const bridge = createBridge(config); bridge.server.listen(0, '127.0.0.1'); await once(bridge.server, 'listening');
  const base = `http://127.0.0.1:${bridge.server.address().port}`;
  t.after(async () => { await bridge.close(); fs.rmSync(dir, { recursive: true, force: true }); });
  const post = (route, data, headers = {}) => fetch(base + route, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(data) });
  const form = (route, data) => fetch(base + route, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(data) });
  async function code() {
    const verifier = crypto.randomBytes(32).toString('base64url');
    const query = new URLSearchParams({ client_id: config.clientId, redirect_uri: config.redirectUris[0], response_type: 'code', resource: config.publicUrl + '/mcp', scope: 'browser', state: 'test-state', code_challenge_method: 'S256', code_challenge: crypto.createHash('sha256').update(verifier).digest('base64url') });
    const page = await fetch(base + '/authorize?' + query); assert.equal(page.status, 200);
    const cookie = page.headers.get('set-cookie').split(';')[0], html = await page.text(), csrf = /name="csrf" value="([^"]+)"/.exec(html)[1];
    const consent = await fetch(base + '/authorize', { method: 'POST', redirect: 'manual', headers: { cookie, 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ csrf, password: config.ownerSecret }) });
    assert.equal(consent.status, 303);
    const location = new URL(consent.headers.get('location')); assert.equal(location.searchParams.get('state'), 'test-state');
    return { grant_type: 'authorization_code', client_id: config.clientId, client_secret: config.clientSecret, code: location.searchParams.get('code'), redirect_uri: config.redirectUris[0], resource: config.publicUrl + '/mcp', code_verifier: verifier };
  }
  const token = async () => { const r = await form('/token', await code()); assert.equal(r.status, 200); return r.json(); };
  const pair = async () => {
    const r = await post('/pair/start', {}, { authorization: `Bearer ${config.adminSecret}` }); assert.equal(r.status, 200); const { code } = await r.json();
    const receipt = await post('/pair/complete', { code }); assert.equal(receipt.status, 200); return { ...await receipt.json(), code };
  };
  const connect = async d => { const ws = new WebSocket(base.replace('http:', 'ws:') + '/device', { headers: { authorization: `Bearer ${d.deviceToken}`, 'x-device-id': d.deviceId } }); await once(ws, 'open'); t.after(() => ws.terminate()); return ws; };
  const client = async () => {
    const tokens = await token(); const c = new Client({ name: 'review-tests', version: '1.0' });
    await c.connect(new StreamableHTTPClientTransport(new URL(base + '/mcp'), { requestInit: { headers: { authorization: `Bearer ${tokens.access_token}` } } }));
    t.after(() => c.close()); return c;
  };
  return { config, base, post, form, code, token, pair, connect, client };
}

test('OAuth discovery, PKCE, one-time codes, resource binding, refresh rotation and revocation', async t => {
  const f = await fixture(t);
  const unauth = await f.post('/mcp', {}); assert.equal(unauth.status, 401); assert.match(unauth.headers.get('www-authenticate'), /resource_metadata/);
  const metadata = await (await fetch(f.base + '/.well-known/oauth-protected-resource/mcp')).json(); assert.equal(metadata.resource, f.config.publicUrl + '/mcp');
  const bad = await f.code(); assert.equal((await f.form('/token', { ...bad, code_verifier: 'x'.repeat(43) })).status, 400); assert.equal((await f.form('/token', bad)).status, 400);
  const correct = await f.code(); assert.equal((await f.form('/token', { ...correct, resource: 'https://wrong.example/mcp' })).status, 400);
  const reply = await f.form('/token', correct); assert.equal(reply.status, 200); const tokens = await reply.json(); assert.equal((await f.form('/token', correct)).status, 400);
  const refresh = { grant_type: 'refresh_token', resource: f.config.publicUrl + '/mcp', client_id: f.config.clientId, client_secret: f.config.clientSecret, refresh_token: tokens.refresh_token };
  const renewed = await f.form('/token', refresh); assert.equal(renewed.status, 200); const next = await renewed.json(); assert.equal((await f.form('/token', refresh)).status, 400);
  assert.equal((await f.post('/mcp', {}, { authorization: `Bearer ${tokens.access_token}` })).status, 401);
  assert.equal((await f.form('/revoke', { client_id: f.config.clientId, client_secret: f.config.clientSecret, token: next.access_token })).status, 200);
  assert.equal((await f.post('/mcp', {}, { authorization: `Bearer ${next.access_token}` })).status, 401);
});

test('rejects arbitrary redirect, missing consent cookie, forged origin, and oversized bodies', async t => {
  const f = await fixture(t);
  assert.equal((await fetch(f.base + '/authorize?redirect_uri=https://evil.example')).status, 400);
  assert.equal((await f.form('/authorize', { csrf: 'invalid', password: f.config.ownerSecret })).status, 403);
  assert.equal((await f.post('/pair/start', {}, { origin: 'https://evil.example', authorization: `Bearer ${f.config.adminSecret}` })).status, 403);
  assert.equal((await f.post('/pair/complete', { code: 'x'.repeat(40000) })).status, 413);
});

test('MCP SDK handshake, listing and actual response correlation; unknown fields cannot override operations', async t => {
  const f = await fixture(t), d = await f.pair(), ws = await f.connect(d), client = await f.client();
  assert.equal((await f.post('/pair/complete', { code: d.code })).status, 400);
  const tools = await client.listTools(); assert.equal(tools.tools.length, 16);
  let count = 0;
  ws.on('message', raw => { const c = JSON.parse(raw); count++; assert.equal(c.op, 'scroll'); assert.equal(c.pixels, 600); assert.ok(c.deadline > Date.now()); ws.send(JSON.stringify({ type: 'result', id: c.id, ok: true, data: { scrollTop: 600 } })); });
  const invalid = await client.callTool({ name: 'browser_scroll', arguments: { tabId: 'tab-12345678', op: 'close_tab' } }); assert.equal(invalid.isError, true); assert.equal(count, 0);
  const result = await client.callTool({ name: 'browser_scroll', arguments: { tabId: 'tab-12345678' } }); assert.equal(result.isError, false); assert.equal(JSON.parse(result.content[0].text).data.scrollTop, 600); assert.equal(count, 1);
});

test('another paired device cannot fulfill a command; ambiguous device selection is rejected', async t => {
  const f = await fixture(t), a = await f.pair(), b = await f.pair(), aw = await f.connect(a), bw = await f.connect(b), client = await f.client();
  const ambiguous = await client.callTool({ name: 'browser_list_tabs', arguments: {} }); assert.equal(ambiguous.isError, true);
  aw.once('message', raw => { const c = JSON.parse(raw); bw.send(JSON.stringify({ type: 'result', id: c.id, ok: true, data: { from: 'wrong' } })); setTimeout(() => aw.send(JSON.stringify({ type: 'result', id: c.id, ok: true, data: { from: 'right' } })), 30); });
  const result = await client.callTool({ name: 'browser_list_tabs', arguments: { deviceId: a.deviceId } }); assert.equal(JSON.parse(result.content[0].text).data.from, 'right');
});

test('socket replacement survives old close and remote revocation blocks reconnection', async t => {
  const f = await fixture(t), d = await f.pair(), old = await f.connect(d), client = await f.client(), oldClosed = once(old, 'close');
  const replacement = await f.connect(d); await oldClosed;
  replacement.on('message', raw => { const c = JSON.parse(raw); replacement.send(JSON.stringify({ type: 'result', id: c.id, ok: true, data: { alive: true } })); });
  assert.equal((await client.callTool({ name: 'browser_task_status', arguments: {} })).isError, false);
  const closed = once(replacement, 'close'); assert.equal((await f.post('/device/revoke', {}, { authorization: `Bearer ${d.deviceToken}`, 'x-device-id': d.deviceId })).status, 200); await closed;
  const failed = new WebSocket(f.base.replace('http:', 'ws:') + '/device', { headers: { authorization: `Bearer ${d.deviceToken}`, 'x-device-id': d.deviceId } }); const [error] = await once(failed, 'error'); assert.match(error.message, /401/);
});

test('device timeout is uncertain, never a success, and sends no automatic retry', async t => {
  const f = await fixture(t, { commandTimeoutMs: 100 }), d = await f.pair(), ws = await f.connect(d), client = await f.client(); let count = 0;
  ws.on('message', () => count++);
  const result = await client.callTool({ name: 'browser_close_tab', arguments: { tabId: 'tab-12345678' } }); assert.equal(result.isError, true); assert.equal(JSON.parse(result.content[0].text).uncertain, true); assert.equal(count, 1);
});

test('screenshots are MCP images and device failures remain tool errors', async t => {
  const f = await fixture(t), d = await f.pair(), ws = await f.connect(d), client = await f.client();
  ws.on('message', raw => { const c = JSON.parse(raw); ws.send(JSON.stringify(c.op === 'screenshot' ? { type: 'result', id: c.id, ok: true, data: { mimeType: 'image/png', base64: 'aGVsbG8=' } } : { type: 'result', id: c.id, ok: false, error: 'Bridge is paused' })); });
  const result = await client.callTool({ name: 'browser_screenshot', arguments: { tabId: 'tab-12345678' } }); assert.equal(result.content[1].type, 'image');
  assert.equal((await client.callTool({ name: 'browser_read_page', arguments: { tabId: 'tab-12345678' } })).isError, true);
});

test('refuses corrupt state, weak secrets and non-HTTPS production configuration', async t => {
  const f = await fixture(t); assert.throws(() => createBridge({ ...f.config, testOnlyAllowHttp: false }), /HTTPS/); assert.throws(() => createBridge({ ...f.config, ownerSecret: 'weak' }), /secrets/);
  fs.writeFileSync(f.config.stateFile, '{bad'); assert.throws(() => createBridge(f.config));
});

test('a saturated work queue still accepts one bounded cancellation command', async t => {
  const f=await fixture(t), d=await f.pair(), ws=await f.connect(d), client=await f.client();
  const received=[];let twenty, cancel;const full=new Promise(r=>twenty=r), cancelled=new Promise(r=>cancel=r);
  ws.on('message',raw=>{const c=JSON.parse(raw);received.push(c);if(received.filter(x=>x.op!=='cancel_task').length===20)twenty();if(c.op==='cancel_task')cancel(c);});
  const work=Array.from({length:20},()=>client.callTool({name:'browser_list_tabs',arguments:{}}));
  await full;
  assert.equal((await client.callTool({name:'browser_list_tabs',arguments:{}})).isError,true);
  const pendingCancel=client.callTool({name:'browser_cancel_task',arguments:{}}), command=await cancelled;
  assert.equal((await client.callTool({name:'browser_cancel_task',arguments:{}})).isError,true);
  ws.send(JSON.stringify({type:'result',id:command.id,ok:true,data:{cancelled:true}}));
  for(const c of received.filter(x=>x.op!=='cancel_task'))ws.send(JSON.stringify({type:'result',id:c.id,ok:false,error:'Cancelled before execution.'}));
  assert.equal((await pendingCancel).isError,false);assert.ok((await Promise.all(work)).every(r=>r.isError));
});
