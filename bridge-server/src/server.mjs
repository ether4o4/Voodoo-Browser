import http from 'node:http';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import crypto from 'node:crypto';
import { WebSocketServer } from 'ws';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { toolDefinitions, devicesSchema, toolResult } from './tools.mjs';
import { loadStore, random, hash, equal, readBody, json, bearer } from './store.mjs';
import { createAuth } from './auth.mjs';

export function createBridge(config) {
  const publicUrl = new URL(config.publicUrl);
  if (publicUrl.origin !== config.publicUrl || publicUrl.username || publicUrl.password || (publicUrl.protocol !== 'https:' && !(config.testOnlyAllowHttp && publicUrl.hostname === '127.0.0.1'))) throw new Error('BRIDGE_PUBLIC_URL must be an HTTPS origin.');
  if (![config.adminSecret, config.ownerSecret, config.clientSecret].every(s => typeof s === 'string' && s.length >= 32) || new Set([config.adminSecret, config.ownerSecret, config.clientSecret]).size !== 3) throw new Error('Use three distinct secrets of at least 32 characters.');
  if (!config.clientId || !config.redirectUris?.length || config.redirectUris.some(s => { const u = new URL(s); return u.protocol !== 'https:' || u.hash || u.username || u.password; })) throw new Error('Configure a client ID and exact HTTPS OAuth redirect URLs.');
  const store = loadStore(config.stateFile), state = store.value, auth = createAuth(config, store);
  const sockets = new Map(), pending = new Map(), rates = new Map();
  const rateLimit = (key, max) => {
    const now = Date.now();
    for (const [k, r] of rates) if (r.until <= now) rates.delete(k);
    const item = rates.get(key) || { count: 0, until: now + 60000 };
    item.count++; rates.set(key, item); return item.count <= max && rates.size <= 10000;
  };
  const devices = () => Object.values(state.devices).filter(d => !d.revoked).map(d => ({ deviceId: d.deviceId, connected: sockets.get(d.deviceId)?.readyState === 1, createdAt: d.createdAt }));
  const deviceAuth = req => {
    const id = req.headers['x-device-id'], d = state.devices[id];
    return d && !d.revoked && equal(d.tokenHash, hash(bearer(req))) ? d : null;
  };
  const failPending = (ws, message) => {
    for (const [id, p] of pending) if (p.ws === ws) p.finish({ ok: false, commandId: id, uncertain: true, error: message });
  };
  const revoke = d => {
    d.revoked = Date.now(); delete d.tokenHash; store.save();
    const ws = sockets.get(d.deviceId); if (ws) { sockets.delete(d.deviceId); failPending(ws, 'Pairing revoked; inspect any in-flight action.'); ws.close(4003, 'Pairing revoked'); }
  };
  const execute = (definition, input) => {
    const args = definition.schema.parse(input);
    const selected = args.deviceId || (devices().length === 1 ? devices()[0].deviceId : null);
    const d = state.devices[selected], ws = sockets.get(selected);
    if (!d || d.revoked) throw new Error('Choose a paired device with browser_list_devices and pass deviceId.');
    if (!ws || ws.readyState !== 1) throw new Error('Device is offline. Open Voodoo and reconnect the bridge.');
    if (pending.size >= 30 || [...pending.values()].filter(p => p.ws === ws).length >= 20) throw new Error('Bridge queue is full.');
    const { deviceId, ...fields } = args;
    const commandId = random(), duration = config.commandTimeoutMs || 30000;
    const command = { ...fields, id: commandId, op: definition.op, deadline: Date.now() + duration };
    return new Promise(resolve => {
      const timer = setTimeout(() => finish({ ok: false, commandId, uncertain: true, error: 'Device timeout. Inspect task status and the page before retrying; do not repeat a write blindly.' }), duration + 250);
      const finish = value => { clearTimeout(timer); pending.delete(commandId); resolve(value); };
      pending.set(commandId, { ws, deviceId: selected, finish });
      ws.send(JSON.stringify({ type: 'command', ...command }), error => { if (error) finish({ ok: false, commandId, uncertain: true, error: 'Connection failed; inspect the page before retrying.' }); });
    });
  };
  const mcp = async (req, res) => {
    if (!auth.authenticated(req)) return auth.unauthorized(res);
    if (req.method !== 'POST') return json(res, 405, { error: 'Use POST for stateless Streamable HTTP.' }, { allow: 'POST' });
    const body = JSON.parse(await readBody(req));
    const server = new McpServer({ name: 'voodoo-browser-bridge', version: '0.2.0' });
    server.registerTool('browser_list_devices', { description: 'List this relay owner’s paired Voodoo devices. Choose deviceId explicitly when multiple devices are paired.', inputSchema: devicesSchema, annotations: { readOnlyHint: true, openWorldHint: false } }, async () => toolResult({ ok: true, data: { devices: devices() } }));
    for (const def of toolDefinitions) server.registerTool(def.name, { description: def.description, inputSchema: def.schema, annotations: def.annotations }, async args => {
      try { return toolResult(await execute(def, args)); } catch (error) { return toolResult({ ok: false, error: error.message }); }
    });
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    res.on('close', () => { void transport.close(); void server.close(); });
    await server.connect(transport); await transport.handleRequest(req, res, body);
  };
  const server = http.createServer(async (req, res) => {
    try {
      if (req.url.length > 8192) return json(res, 414, { error: 'URL too long' });
      const pathname = new URL(req.url, config.publicUrl).pathname;
      // Bind to loopback behind one TLS proxy. Do not trust forwarded identity headers.
      if (req.headers.origin && req.headers.origin !== config.publicUrl) return json(res, 403, { error: 'Origin rejected' });
      const ip = req.socket.remoteAddress;
      const authPath = ['/authorize', '/token', '/pair/complete'].includes(pathname);
      if (!rateLimit(`${ip}:${authPath ? 'auth' : 'api'}`, authPath ? 30 : 600)) return json(res, 429, { error: 'Rate limit exceeded' }, { 'retry-after': '60' });
      if (await auth.handle(req, res, pathname)) return;
      if (req.method === 'GET' && pathname === '/healthz') return json(res, 200, { ok: true });
      if (req.method === 'POST' && pathname === '/pair/start') {
        if (!equal(bearer(req), config.adminSecret)) return json(res, 401, { error: 'Unauthorized' });
        for (const [key, p] of Object.entries(state.pairings)) if (p.expiresAt < Date.now()) delete state.pairings[key];
        if (Object.keys(state.pairings).length >= 20 || devices().length >= 20) return json(res, 409, { error: 'Pairing capacity reached' });
        // 64 random bits, displayed as 16 hexadecimal characters. No short numeric guessing window.
        const code = crypto.randomBytes(8).toString('hex').toUpperCase(), expiresAt = Date.now() + 300000;
        state.pairings[hash(code)] = { expiresAt }; store.save();
        return json(res, 200, { ok: true, code, expiresAt });
      }
      if (req.method === 'POST' && pathname === '/pair/complete') {
        const b = JSON.parse(await readBody(req)), code = String(b.code || '').trim().toUpperCase(), key = hash(code), p = state.pairings[key];
        if (!p || p.expiresAt <= Date.now() || devices().length >= 20) return json(res, 400, { ok: false, error: 'Pairing code expired or already used' });
        delete state.pairings[key];
        const deviceId = `dev-${random()}`, deviceToken = random();
        state.devices[deviceId] = { deviceId, tokenHash: hash(deviceToken), createdAt: Date.now() }; store.save();
        return json(res, 200, { ok: true, deviceId, deviceToken, accountId: 'relay-owner' });
      }
      if (req.method === 'POST' && pathname === '/device/revoke') {
        const d = deviceAuth(req); if (!d) return json(res, 401, { error: 'Unauthorized' });
        revoke(d); return json(res, 200, { ok: true });
      }
      if (req.method === 'POST' && pathname === '/admin/device/revoke') {
        if (!equal(bearer(req), config.adminSecret)) return json(res, 401, { error: 'Unauthorized' });
        const b = JSON.parse(await readBody(req)), d = state.devices[b.deviceId];
        if (!d) return json(res, 404, { error: 'Unknown device' });
        revoke(d); return json(res, 200, { ok: true });
      }
      if (pathname === '/mcp') return await mcp(req, res);
      json(res, 404, { error: 'Not found' });
    } catch (error) {
      if (!res.headersSent) json(res, error.status || 400, { error: error.status === 413 ? 'Request too large' : 'Request rejected' });
      else res.end();
    }
  });
  server.maxConnections = 100; server.requestTimeout = 40000; server.headersTimeout = 10000;
  const wss = new WebSocketServer({ noServer: true, maxPayload: 4500000, perMessageDeflate: false });
  server.on('upgrade', (req, socket, head) => {
    const d = deviceAuth(req);
    if (req.url !== '/device' || !d || (req.headers.origin && req.headers.origin !== config.publicUrl) || !rateLimit(`ws:${req.socket.remoteAddress}`, 60)) { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return; }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, d));
  });
  wss.on('connection', (ws, d) => {
    const old = sockets.get(d.deviceId); if (old) { failPending(old, 'Device reconnected; inspect the interrupted action.'); old.close(4001, 'Connection replaced'); }
    sockets.set(d.deviceId, ws);
    ws.on('error', () => failPending(ws, 'Device connection failed; action outcome is uncertain.'));
    ws.on('message', raw => {
      try {
        const message = JSON.parse(raw), p = pending.get(message.id);
        if (message.type !== 'result' || !p || p.ws !== ws || p.deviceId !== d.deviceId || sockets.get(d.deviceId) !== ws || d.revoked || typeof message.ok !== 'boolean') return;
        const { id, type, ...result } = message;
        if (result.data?.base64 && (!/^[A-Za-z0-9+/]*={0,2}$/.test(result.data.base64) || result.data.mimeType !== 'image/png')) return;
        p.finish({ ...result, commandId: id });
      } catch { /* Invalid device messages are never forwarded to a caller. */ }
    });
    ws.on('close', () => { if (sockets.get(d.deviceId) === ws) sockets.delete(d.deviceId); failPending(ws, 'Device disconnected; action outcome is uncertain.'); });
  });
  return {
    server,
    async close() { for (const ws of wss.clients) ws.terminate(); for (const [, p] of pending) p.finish({ ok: false, uncertain: true, error: 'Relay stopped' }); wss.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const env = process.env;
  const bridge = createBridge({ publicUrl: env.BRIDGE_PUBLIC_URL, stateFile: path.resolve(env.BRIDGE_STATE_FILE || './data/state.json'), adminSecret: env.BRIDGE_ADMIN_SECRET, ownerSecret: env.BRIDGE_OWNER_SECRET, clientId: env.BRIDGE_OAUTH_CLIENT_ID, clientSecret: env.BRIDGE_OAUTH_CLIENT_SECRET, redirectUris: (env.BRIDGE_OAUTH_REDIRECT_URIS || '').split(',').map(x => x.trim()).filter(Boolean) });
  bridge.server.listen(Number(env.BRIDGE_PORT || 8787), env.BRIDGE_HOST || '127.0.0.1', () => console.log('Voodoo bridge relay listening.'));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { void bridge.close().then(() => process.exit(0)); });
}
