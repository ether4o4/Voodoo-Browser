import crypto from 'node:crypto';
import { random, hash, equal, readBody, json, bearer } from './store.mjs';

/** A single-owner deployment with a pre-registered OAuth client, PKCE and rotating refresh tokens. */
export function createAuth(config, store) {
  const { publicUrl, clientId, clientSecret, ownerSecret, redirectUris } = config;
  const resource = `${publicUrl}/mcp`, transactions = new Map(), codes = new Map();
  const state = store.value;
  const prune = () => {
    const now = Date.now();
    for (const map of [transactions, codes]) for (const [key, entry] of map) if (entry.expires <= now) map.delete(key);
    for (const table of [state.access, state.refresh]) for (const [key, entry] of Object.entries(table)) if (entry.expires <= now) delete table[key];
  };
  const credentials = (req, form) => {
    let id = form.get('client_id'), secret = form.get('client_secret');
    if (req.headers.authorization?.startsWith('Basic ')) {
      const decoded = Buffer.from(req.headers.authorization.slice(6), 'base64').toString(), at = decoded.indexOf(':');
      id = decodeURIComponent(decoded.slice(0, at)); secret = decodeURIComponent(decoded.slice(at + 1));
    }
    return equal(id, clientId) && equal(secret, clientSecret);
  };
  const issue = () => {
    prune();
    if (Object.keys(state.refresh).length >= 1000) throw new Error('Token capacity reached; revoke an old grant.');
    const access_token = random(), refresh_token = random(), grant = random();
    state.access[hash(access_token)] = { grant, expires: Date.now() + 3600000, resource };
    state.refresh[hash(refresh_token)] = { grant, expires: Date.now() + 30 * 86400000, resource };
    store.save();
    return { access_token, token_type: 'Bearer', expires_in: 3600, refresh_token, scope: 'browser' };
  };
  const revokeGrant = grant => {
    for (const table of [state.access, state.refresh]) for (const [key, entry] of Object.entries(table)) if (entry.grant === grant) delete table[key];
  };
  const unauthorized = res => json(res, 401, { error: 'invalid_token' }, { 'www-authenticate': `Bearer resource_metadata="${publicUrl}/.well-known/oauth-protected-resource/mcp"` });
  return {
    resource, unauthorized,
    authenticated(req) { const entry = state.access[hash(bearer(req))]; return Boolean(entry && entry.expires > Date.now() && entry.resource === resource); },
    async handle(req, res, pathname) {
      prune();
      if (req.method === 'GET' && ['/.well-known/oauth-protected-resource', '/.well-known/oauth-protected-resource/mcp'].includes(pathname)) {
        json(res, 200, { resource, authorization_servers: [publicUrl], scopes_supported: ['browser'], bearer_methods_supported: ['header'] }); return true;
      }
      if (req.method === 'GET' && pathname === '/.well-known/oauth-authorization-server') {
        json(res, 200, { issuer: publicUrl, authorization_endpoint: `${publicUrl}/authorize`, token_endpoint: `${publicUrl}/token`, revocation_endpoint: `${publicUrl}/revoke`, response_types_supported: ['code'], grant_types_supported: ['authorization_code', 'refresh_token'], token_endpoint_auth_methods_supported: ['client_secret_post', 'client_secret_basic'], code_challenge_methods_supported: ['S256'], scopes_supported: ['browser'] }); return true;
      }
      if (req.method === 'GET' && pathname === '/authorize') {
        const q = new URL(req.url, publicUrl).searchParams;
        const redirect = q.get('redirect_uri'), challenge = q.get('code_challenge');
        if (q.get('client_id') !== clientId || !redirectUris.includes(redirect) || q.get('resource') !== resource || q.get('response_type') !== 'code' || q.get('code_challenge_method') !== 'S256' || !/^[A-Za-z0-9_-]{43}$/.test(challenge || '') || (q.get('scope') || 'browser') !== 'browser' || (q.get('state') || '').length > 1024) {
          json(res, 400, { error: 'invalid_request' }); return true;
        }
        if (transactions.size >= 200) { json(res, 429, { error: 'Try again later' }); return true; }
        const csrf = random(), cookie = random();
        transactions.set(hash(csrf), { cookie: hash(cookie), redirect, challenge, state: q.get('state') || '', expires: Date.now() + 300000 });
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'content-security-policy': "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'", 'referrer-policy': 'no-referrer', 'set-cookie': `voodoo_oauth=${cookie}; Path=/authorize; HttpOnly; SameSite=Lax${publicUrl.startsWith('https:') ? '; Secure' : ''}; Max-Age=300` });
        res.end(`<!doctype html><html><meta name="viewport" content="width=device-width"><title>Connect Voodoo Browser</title><h1>Connect Voodoo Browser</h1><p>This client will be able to read and control allowed websites on your paired devices while you enable the bridge. Website content and screenshots can be sent to the connected client. You can pause or revoke access on the phone.</p><form method="post" action="/authorize"><input type="hidden" name="csrf" value="${csrf}"><label>Relay owner password <input type="password" name="password" autocomplete="current-password" required></label><button>Authorize browser access</button></form></html>`); return true;
      }
      if (req.method === 'POST' && pathname === '/authorize') {
        const form = new URLSearchParams(await readBody(req));
        const key = hash(form.get('csrf') || ''), t = transactions.get(key);
        const cookie = /(?:^|;\s*)voodoo_oauth=([A-Za-z0-9_-]+)/.exec(req.headers.cookie || '')?.[1] || '';
        transactions.delete(key);
        if (!t || t.expires <= Date.now() || !equal(t.cookie, hash(cookie)) || !equal(form.get('password'), ownerSecret) || (req.headers.origin && req.headers.origin !== publicUrl)) { json(res, 403, { error: 'Authorization rejected; restart sign-in.' }); return true; }
        const code = random(); codes.set(hash(code), { ...t, expires: Date.now() + 60000 });
        const destination = new URL(t.redirect); destination.searchParams.set('code', code); destination.searchParams.set('state', t.state);
        res.writeHead(303, { location: destination.href, 'cache-control': 'no-store', 'set-cookie': 'voodoo_oauth=; Path=/authorize; HttpOnly; SameSite=Lax; Max-Age=0' }); res.end(); return true;
      }
      if (req.method === 'POST' && ['/token', '/revoke'].includes(pathname)) {
        const form = new URLSearchParams(await readBody(req));
        if (!credentials(req, form)) { json(res, 401, { error: 'invalid_client' }); return true; }
        if (pathname === '/revoke') {
          const key = hash(form.get('token') || ''), entry = state.access[key] || state.refresh[key];
          if (entry) { revokeGrant(entry.grant); store.save(); }
          json(res, 200, {}); return true;
        }
        if (form.get('resource') !== resource) { json(res, 400, { error: 'invalid_target' }); return true; }
        if (form.get('grant_type') === 'authorization_code') {
          const key = hash(form.get('code') || ''), code = codes.get(key), verifier = form.get('code_verifier') || '';
          codes.delete(key);
          const challenge = crypto.createHash('sha256').update(verifier).digest('base64url');
          if (!code || code.expires <= Date.now() || code.redirect !== form.get('redirect_uri') || !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier) || !equal(code.challenge, challenge)) { json(res, 400, { error: 'invalid_grant' }); return true; }
        } else if (form.get('grant_type') === 'refresh_token') {
          const entry = state.refresh[hash(form.get('refresh_token') || '')];
          if (!entry || entry.expires <= Date.now() || entry.resource !== resource) { json(res, 400, { error: 'invalid_grant' }); return true; }
          revokeGrant(entry.grant);
        } else { json(res, 400, { error: 'unsupported_grant_type' }); return true; }
        json(res, 200, issue()); return true;
      }
      return false;
    }
  };
}
