const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { once } = require('node:events');
const { startEmbeddedRendererAndVibeLinkServer } = require('./embeddedServer.cjs');

test('local developer Hub token is read from edition directory and never returned to renderer', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'vibelink-desktop-test-'));
  const previous = { APPDATA: process.env.APPDATA, LOCALAPPDATA: process.env.LOCALAPPDATA, VIBELINK_HUB: process.env.VIBELINK_HUB, VIBELINK_TOKEN: process.env.VIBELINK_TOKEN };
  process.env.APPDATA = path.join(dir, 'roaming');
  process.env.LOCALAPPDATA = dir;
  delete process.env.VIBELINK_TOKEN;
  const tokenDir = path.join(dir, 'vibelink-mesh', 'data', 'dev');
  fs.mkdirSync(tokenDir, { recursive: true });
  const secret = 'temporary-test-token-never-serialize';
  fs.writeFileSync(path.join(tokenDir, 'token.txt'), secret);
  const received = [];
  const hub = http.createServer((req, res) => {
    received.push(req.headers.authorization);
    res.setHeader('Content-Type', 'application/json');
    res.end(req.url === '/api/devices' ? JSON.stringify({ devices: [{ id: 'test-agent', online: true }] }) : '[]');
  }).listen(0, '127.0.0.1');
  await once(hub, 'listening');
  process.env.VIBELINK_HUB = `http://127.0.0.1:${hub.address().port}`;
  const server = startEmbeddedRendererAndVibeLinkServer({ rendererRoot: dir, pdfjsRoot: dir, port: 0 });
  await once(server, 'listening');
  t.after(async () => {
    await Promise.all([new Promise(r => server.close(r)), new Promise(r => hub.close(r))]);
    for (const [key, value] of Object.entries(previous)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; }
    fs.rmSync(dir, { recursive: true, force: true });
  });
  const text = await new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: server.address().port, path: '/api/vibelink/state', headers: { host: '127.0.0.1:0' } }, res => {
      let body = ''; res.on('data', c => { body += c; }); res.on('end', () => resolve(body));
    }).on('error', reject);
  });
  const snapshot = JSON.parse(text);
  assert.equal(snapshot.hubOk, true);
  assert.equal(snapshot.hasToken, true);
  assert.equal(snapshot.devices[0].id, 'test-agent');
  assert.deepEqual(received, Array(4).fill(`Bearer ${secret}`));
  assert.equal(text.includes(secret), false);
  const forbidden = await new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: server.address().port, path: '/api/vibelink/state', headers: { host: '127.0.0.1:0', origin: 'https://untrusted.example' } }, res => {
      res.resume(); res.on('end', () => resolve(res.statusCode));
    }).on('error', reject);
  });
  assert.equal(forbidden, 403);
  assert.equal(received.length, 4, 'untrusted page cannot reach the authenticated Hub');
  const save = await new Promise((resolve, reject) => {
    const request = http.request({ host: '127.0.0.1', port: server.address().port, path: '/api/vibelink/action', method: 'POST', headers: { host: '127.0.0.1:0', 'content-type': 'application/json' } }, res => {
      res.resume(); res.on('end', () => resolve(res.statusCode));
    }).on('error', reject);
    request.end(JSON.stringify({ action: 'save-settings', token: 'fixture-not-a-real-token', hub: 'http://127.0.0.1:18995' }));
  });
  assert.equal(save, 400);
  assert.equal(fs.existsSync(path.join(process.env.APPDATA, 'vibelink', 'config.json')), false);


});
