/**
 * 融合版本机服务（127.0.0.1:5174）：给 VibeLink 桌面壳提供静态文件，
 * 以及 vibelink 控制台用的 /api/vibelink/state、/api/vibelink/action。
 *
 * 这份文件原先只存在于构建产物 .vite/build/vibelink-embedded-server.js（被 git 忽略、重建即丢），
 * 现在收回为源码，由 bootstrap-electron.ts 在融合模式（CINDY_LOCAL_RELAY_URL 或
 * CINDY_VIBELINK_EMBEDDED=1）下启动。
 *
 * 安全边界（收回源码时补上的）：
 * - 只监听 127.0.0.1（原来监听所有网卡，热点上任何设备都能访问）。
 * - 校验 Host（防 DNS rebinding）和 Origin（防网页 CSRF 触发 /api/vibelink/action）。
 * - /api/vibelink/state 不再返回 hub token（原来把管理员 token 明文返回给任何请求方）。
 */
const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFile } = require('node:child_process');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.wasm': 'application/wasm',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.mp4': 'video/mp4',
  '.bcmap': 'application/octet-stream',
  '.pfb': 'application/octet-stream',
};

function loadHubSettings() {
  const hub = String(process.env.VIBELINK_HUB || 'http://127.0.0.1:18995').replace(/\/+$/, '');
  let token = process.env.VIBELINK_TOKEN || '';
  // A local developer token never authenticates a remote or beta Hub. Explicit
  // connection settings win, and a remote connection cannot silently switch to localhost.
  let local = false;
  try { local = ['127.0.0.1', 'localhost', '[::1]'].includes(new URL(hub).hostname); } catch {}
  if (!token && local) {
    const localAppData = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
    for (const parts of [['data', 'dev', 'token.txt'], ['data', 'token.txt']]) {
      try {
        token = fs.readFileSync(path.join(localAppData, 'vibelink-mesh', ...parts), 'utf8').trim();
        if (token) break;
      } catch {}
    }
  }
  return { hub, token };
}

function hubRequest(hubUrl, method, reqPath, token, body, contentType = 'application/json') {
  return new Promise((resolve, reject) => {
    try {
      const u = new URL(reqPath, hubUrl.endsWith('/') ? hubUrl : hubUrl + '/');
      const transport = u.protocol === 'https:' ? https : u.protocol === 'http:' ? http : null;
      if (!transport) throw new Error('Hub URL must use HTTP or HTTPS');
      const req = transport.request(
        u,
        {
          method,
          headers: {
            ...(token ? { Authorization: `Bearer ${token}`, 'x-mesh-token': token } : {}),
            ...(body != null
              ? {
                  'Content-Type': contentType,
                  'Content-Length': Buffer.byteLength(body),
                }
              : {}),
          },
          timeout: 2500,
        },
        (res) => {
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () => {
            const text = Buffer.concat(chunks).toString('utf8');
            if (res.statusCode >= 200 && res.statusCode < 300) {
              resolve(text);
            } else {
              reject(new Error(`HTTP ${res.statusCode}`));
            }
          });
        },
      );
      req.on('error', reject);
      req.on('timeout', () => {
        req.destroy(new Error('timeout'));
      });
      if (body != null) req.write(body);
      req.end();
    } catch (err) {
      reject(err);
    }
  });
}

let cachedTailscale = { state: 'Missing', ip: '' };
let lastTailscaleCheck = 0;

function checkTailscale() {
  const now = Date.now();
  if (now - lastTailscaleCheck < 4000) {
    return Promise.resolve(cachedTailscale);
  }
  lastTailscaleCheck = now;
  const bin = 'C:\\Program Files\\Tailscale\\tailscale.exe';
  if (!fs.existsSync(bin)) {
    cachedTailscale = { state: 'Missing', ip: '' };
    return Promise.resolve(cachedTailscale);
  }
  return new Promise((resolve) => {
    execFile(
      bin,
      ['status', '--json'],
      { timeout: 4000, maxBuffer: 16 * 1024 * 1024, windowsHide: true },
      (err, stdout) => {
        if (err || !stdout) {
          resolve(cachedTailscale);
          return;
        }
        try {
          const v = JSON.parse(stdout);
          const state = v.BackendState || 'Unknown';
          const ip = (v.TailscaleIPs && v.TailscaleIPs[0]) || '';
          cachedTailscale = { state, ip };
        } catch {}
        resolve(cachedTailscale);
      },
    );
  });
}

function normalizeDevice(d) {
  if (!d || typeof d !== 'object') return d;
  const listeningPorts = Array.isArray(d.listeningPorts)
    ? d.listeningPorts
    : d.ports && typeof d.ports === 'object'
      ? Object.entries(d.ports)
          .filter(([, open]) => Boolean(open))
          .map(([p]) => Number(p))
      : [];
  const probes = Array.isArray(d.probes)
    ? d.probes
    : d.latency && typeof d.latency === 'object'
      ? [
          {
            target: d.latency.target || '',
            tcpMs: d.latency.tcpMs ?? null,
            httpMs: d.latency.connectMs ?? d.latency.httpMs ?? null,
            ok: true,
          },
        ]
      : [];
  const systemProxy = d.systemProxy || (d.proxy ? { enabled: Boolean(d.proxy.enabled), server: d.proxy.server || '' } : undefined);
  return {
    ...d,
    os: d.os || (d.platform ? String(d.platform).split(' ')[0] : 'unknown'),
    systemProxy,
    probes,
    listeningPorts,
    memAvailableMb: d.memAvailableMb ?? (d.mem ? d.mem.free : undefined),
    memTotalMb: d.memTotalMb ?? (d.mem ? d.mem.total : undefined),
    lastSeenMs: d.lastSeenMs ?? d.lastSeen ?? d.ts,
  };
}

function resolveLanIp() {
  const ifaces = os.networkInterfaces();
  const candidates = [];
  for (const list of Object.values(ifaces)) {
    for (const addr of list || []) {
      if (addr.family === 'IPv4' && !addr.internal) {
        candidates.push(addr.address);
      }
    }
  }
  return (
    candidates.find((ip) => ip.startsWith('192.168.5.')) ||
    candidates.find((ip) => ip.startsWith('192.168.137.')) ||
    candidates.find((ip) => ip.startsWith('192.168.')) ||
    candidates[0] ||
    '127.0.0.1'
  );
}

function readJsonBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        resolve({});
      }
    });
    req.on('error', () => resolve({}));
  });
}

function isAllowedLocalRequest(req, port) {
  const allowedHosts = new Set([`localhost:${port}`, `127.0.0.1:${port}`]);
  const host = String(req.headers.host || '').toLowerCase();
  if (!allowedHosts.has(host)) return false;
  const origin = req.headers.origin;
  // 同源 fetch / 页面导航不带 Origin 或带本机 Origin；其他网页发来的请求一律拒绝
  if (origin !== undefined && origin !== 'null') {
    return allowedHosts.has(String(origin).toLowerCase().replace(/^https?:\/\//, ''));
  }
  if (origin === 'null') return false;
  return true;
}

function startEmbeddedRendererAndVibeLinkServer({ rendererRoot, pdfjsRoot, port = 5174 }) {
  // Hub/Agent lifecycle is managed by VibeLink; this UI never launches legacy relays.

  const server = http.createServer(async (req, res) => {
    try {
      if (!isAllowedLocalRequest(req, port)) {
        res.writeHead(403, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'forbidden: local origin only' }));
        return;
      }
      const reqUrl = new URL(req.url || '/', `http://localhost:${port}`);
      const pathname = decodeURIComponent(reqUrl.pathname);

      // 1. VibeLink State API
      if (pathname === '/api/vibelink/state' && req.method === 'GET') {
        const { hub, token } = loadHubSettings();
        const [devicesRes, eventsRes, configsRes, policyRes, tailscale] = await Promise.all([
          hubRequest(hub, 'GET', '/api/devices', token).catch((e) => ({ __err: e.message })),
          hubRequest(hub, 'GET', '/api/events?limit=60', token).catch(() => '[]'),
          hubRequest(hub, 'GET', '/api/configs', token).catch(() => '[]'),
          hubRequest(hub, 'GET', '/api/policy', token).catch(() => 'null'),
          checkTailscale(),
        ]);

        let hubOk = true;
        let hubError = null;
        let devices = [];
        if (devicesRes && typeof devicesRes === 'object' && devicesRes.__err) {
          hubOk = false;
          hubError = devicesRes.__err;
        } else {
          try {
            const parsed = JSON.parse(devicesRes);
            const rawList = Array.isArray(parsed) ? parsed : (parsed && parsed.devices) || [];
            devices = rawList.map(normalizeDevice);
          } catch {}
        }

        let events = [];
        try {
          const parsed = JSON.parse(eventsRes);
          const rawEv = Array.isArray(parsed) ? parsed : (parsed && parsed.events) || [];
          events = rawEv.map(e => ({ ...e, ts: e.ts ?? e.at, deviceId: e.deviceId ?? e.device ?? 'hub', detail: e.detail ?? e.message ?? '' }));
        } catch {}

        let configs = [];
        try {
          const parsed = JSON.parse(configsRes);
          configs = Array.isArray(parsed) ? parsed : (parsed && parsed.configs) || [];
        } catch {}

        let policy = null;
        try {
          policy = JSON.parse(policyRes);
        } catch {}

        const lanIp = resolveLanIp();
        let finalTailscale = tailscale;
        if ((!finalTailscale || finalTailscale.state === 'Missing') && Array.isArray(devices)) {
          const pcDev = devices.find((d) => d.id === 'pc');
          const tsIp = pcDev && Array.isArray(pcDev.ips) ? pcDev.ips.find((ip) => String(ip).startsWith('100.')) : null;
          if (tsIp) {
            finalTailscale = { state: 'Running', ip: tsIp };
          }
        }
        const payload = {
          hubOk,
          hubError,
          hubUrl: hub,
          hasToken: Boolean(token),
          devices: Array.isArray(devices) ? devices : [],
          events: Array.isArray(events) ? events : [],
          configs: Array.isArray(configs) ? configs : [],
          policy,
          tailscale: finalTailscale,
          cindyDeviceLink: {
            relayUrl: `ws://${lanIp}:47820/api/device-link/ws`,
            hostDeviceId: `host-${os.hostname().toLowerCase()}`,
            hostName: os.hostname().toLowerCase(),
            lanIp,
            apkUrl: `http://${lanIp}:18995/apk`,
          },
        };

        res.writeHead(200, {
          'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store',
        });
        res.end(JSON.stringify(payload));
        return;
      }

      // 2. VibeLink Action API
      if (pathname === '/api/vibelink/action' && req.method === 'POST') {
        const body = await readJsonBody(req);
        const { hub, token } = loadHubSettings();
        const action = body.action;

        if (action === 'command') {
          const cmdBody = JSON.stringify({
            deviceId: body.deviceId,
            kind: body.kind,
            args: body.args || {},
          });
          await hubRequest(hub, 'POST', '/api/commands', token, cmdBody, 'application/json');
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true }));
          return;
        }

        if (action === 'upload-config') {
          await hubRequest(
            hub,
            'POST',
            `/api/config/${encodeURIComponent(body.name || 'config.yaml')}`,
            token,
            String(body.body || ''),
            'text/plain',
          );
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true }));
          return;
        }

        if (action === 'save-policy') {
          await hubRequest(
            hub,
            'POST',
            '/api/policy',
            token,
            String(body.body || '{}'),
            'application/json',
          );
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true }));
          return;
        }

        if (action === 'hub-pair') {
          // 扫码配对：向 hub 要一个一次性配对链接（vibelink://pair?...，10 分钟，需要管理员 token），
          // 渲染层把 link 画成二维码给 Mika 扫。这个服务只接受本机同源请求，link 不会出本机。
          let result;
          try {
            const raw = await hubRequest(hub, 'POST', '/api/pair/start', token, JSON.stringify({ name: String(body.name || 'phone') }), 'application/json');
            result = JSON.parse(raw);
            if (!result || !result.link) {
              result = { error: 'hub 不支持扫码配对，请先升级 vibelink-hub（运行 install\\deploy-secure-hub.ps1）' };
            }
          } catch (e) {
            const msg = e.message || String(e);
            result = { error: /40[13]/.test(msg) ? '缺少 hub 管理员 token：在「设置」里填 hub 数据目录 token.txt 的内容' : msg };
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(result));
          return;
        }

        if (action === 'hub-pair-status') {
          // 轮询配对进度：pending / scanned（手机已扫码，等手机上点「连接」）/ paired / expired
          let result;
          const id = String(body.id || '');
          if (!/^pr_[0-9a-f]{1,32}$/.test(id)) {
            result = { error: 'bad id' };
          } else {
            try {
              result = JSON.parse(await hubRequest(hub, 'GET', `/api/pair/status/${id}`, token));
            } catch (e) {
              result = { error: e.message || String(e) };
            }
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(result));
          return;
        }

        if (action === 'tailscale-up') {
          const bin = 'C:\\Program Files\\Tailscale\\tailscale.exe';
          if (fs.existsSync(bin)) {
            execFile(bin, ['up'], { windowsHide: true }, () => {});
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true }));
          return;
        }

        if (action === 'create-pairing-code') {
          const lanIp = resolveLanIp();
          let result = { error: 'Relay not reachable' };
          try {
            const raw = await hubRequest(
              'http://127.0.0.1:47820',
              'POST',
              `/api/device-link/relay/local-join-link?base=${encodeURIComponent(`http://${lanIp}:47820`)}`,
              '',
              '{}',
              'application/json',
            );
            const parsed = JSON.parse(raw);
            result = {
              pairingCode: parsed.code,
              pairingLink: parsed.link,
            };
          } catch (e) {
            result = { error: e.message || String(e) };
          }
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(result));
          return;
        }

        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'unknown action' }));
        return;
      }

      if (pathname.startsWith('/api/')) {
        res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'Unsupported local service endpoint' }));
        return;
      }

      // 4. pdfjs assets
      const pdfMatch = pathname.match(/^\/pdfjs\/(cmaps|standard_fonts)\/([^?#]+)/);
      if (pdfMatch && pdfjsRoot) {
        const abs = path.join(pdfjsRoot, pdfMatch[1], pdfMatch[2]);
        if (abs.startsWith(path.join(pdfjsRoot, pdfMatch[1])) && fs.existsSync(abs)) {
          const stat = fs.statSync(abs);
          if (stat.isFile()) {
            res.writeHead(200, {
              'Content-Length': stat.size,
              'Content-Type': MIME_TYPES[path.extname(abs).toLowerCase()] || 'application/octet-stream',
            });
            fs.createReadStream(abs).pipe(res);
            return;
          }
        }
      }

      // 4. Static renderer files from rendererRoot (.vite/renderer/main_window)
      let relPath = pathname === '/' ? '/index.html' : pathname;
      let filePath = path.join(rendererRoot, relPath);
      if (!filePath.startsWith(rendererRoot)) {
        res.writeHead(403);
        res.end();
        return;
      }
      if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
        filePath = path.join(rendererRoot, 'index.html');
      }
      if (!fs.existsSync(filePath)) {
        res.writeHead(404);
        res.end('Renderer bundle not found');
        return;
      }
      const ext = path.extname(filePath).toLowerCase();
      const mime = MIME_TYPES[ext] || 'application/octet-stream';
      const stat = fs.statSync(filePath);
      res.writeHead(200, {
        'Content-Type': mime,
        'Content-Length': stat.size,
        'Cache-Control': 'no-store',
      });
      fs.createReadStream(filePath).pipe(res);
    } catch (err) {
      res.writeHead(500);
      res.end(String(err));
    }
  });

  server.on('error', (err) => {
    console.warn('[vibelink-embedded-server] listen error:', err.message);
  });

  server.listen(port, '127.0.0.1', () => {
    console.log(`[vibelink-embedded-server] serving Cindy+VibeLink UI on http://localhost:${port}`);
  });

  return server;
}

module.exports = {
  startEmbeddedRendererAndVibeLinkServer,
};
