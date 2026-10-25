import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
export const random = () => crypto.randomBytes(32).toString('base64url');
export const hash = value => crypto.createHash('sha256').update(value).digest('hex');
export const equal = (a, b) => typeof a === 'string' && typeof b === 'string' && crypto.timingSafeEqual(Buffer.from(hash(a)), Buffer.from(hash(b)));
export function loadStore(filename) {
  let value = { version: 1, devices: {}, pairings: {}, access: {}, refresh: {} };
  if (fs.existsSync(filename)) {
    value = JSON.parse(fs.readFileSync(filename, 'utf8'));
    if (value.version !== 1 || !['devices', 'pairings', 'access', 'refresh'].every(key => value[key] && typeof value[key] === 'object')) throw new Error('Unsupported or corrupt state; do not overwrite it.');
  }
  const save = () => {
    fs.mkdirSync(path.dirname(filename), { recursive: true, mode: 0o700 });
    const temp = `${filename}.tmp`;
    const fd = fs.openSync(temp, 'w', 0o600);
    try { fs.writeFileSync(fd, JSON.stringify(value)); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    fs.renameSync(temp, filename);
    const dir = fs.openSync(path.dirname(filename), 'r');
    try { fs.fsyncSync(dir); } finally { fs.closeSync(dir); }
  };
  return { value, save };
}
export function readBody(req, limit = 32768) {
  return new Promise((resolve, reject) => {
    let size = 0, chunks = [], failed = false;
    req.on('data', chunk => {
      if (failed) return;
      size += chunk.length;
      if (size > limit) { failed = true; chunks = []; reject(Object.assign(new Error('Request too large'), { status: 413 })); return; }
      chunks.push(chunk);
    });
    req.on('end', () => { if (!failed) resolve(Buffer.concat(chunks).toString('utf8')); });
    req.on('error', reject);
    req.on('aborted', () => reject(new Error('Request aborted')));
  });
}
export function json(res, status, data, headers = {}) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', ...headers });
  res.end(JSON.stringify(data));
}
export const bearer = req => /^Bearer /i.test(req.headers.authorization || '') ? req.headers.authorization.slice(7) : '';
