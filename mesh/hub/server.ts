// mesh hub - device registry, heartbeat, command queue, config store, static UI.
// Zero dependencies. Runs directly on Node >= 22.6 (node hub/server.ts).
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, statSync, renameSync } from 'node:fs'
import { join, extname, basename, dirname } from 'node:path'
import { randomBytes, randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { execFile } from 'node:child_process'
import { Readable } from 'node:stream'
import {
  ERROR_STATUS,
  HEADER_INSTANCE,
  HEADER_PROTOCOL,
  HEADER_REQUEST_ID,
  HEADER_TOKEN,
  LIMITS,
  MESH_PROTOCOL,
  MESH_PROTOCOL_MIN,
  type MeshErrorCode
} from '../shared/protocol.ts'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = dirname(HERE)
const DATA = process.env.MESH_DATA || join(ROOT, 'data')
const WEB = join(ROOT, 'web')
const CONFIGS = join(DATA, 'configs')
const PORT = Number(process.env.MESH_PORT || 18994)
const VERSION = '0.3.0'
const OFFLINE_AFTER_MS = Math.max(1_000, Number(process.env.MESH_OFFLINE_AFTER_MS || 20_000) || 20_000)
const CONTROL_TIMEOUT_MS = 30_000
const SSE_CONNECT_TIMEOUT_MS = 15_000

for (const d of [DATA, CONFIGS]) if (!existsSync(d)) mkdirSync(d, { recursive: true })

// ---------- token ----------
const tokenFile = join(DATA, 'token.txt')
if (!existsSync(tokenFile)) writeFileSync(tokenFile, randomBytes(24).toString('hex') + '\n', 'utf8')
const TOKEN = readFileSync(tokenFile, 'utf8').trim()

// ---------- tiny json store ----------
function loadJson<T>(file: string, fallback: T): T {
  try { return JSON.parse(readFileSync(file, 'utf8')) as T } catch { return fallback }
}
function saveJson(file: string, value: unknown) {
  const tmp = file + '.tmp'
  writeFileSync(tmp, JSON.stringify(value, null, 2), 'utf8')
  renameSync(tmp, file)
}

type Device = {
  id: string
  hostname: string
  platform: string
  arch: string
  agentVersion: string
  protocolVersion: number
  controlPort: number | null
  ips: string[]
  gateway: string | null
  proxy: { enabled: boolean; server: string | null } | null
  ports: Record<string, boolean>
  opencode: { port: number } | null
  latency: { target: string; tcpMs: number | null; connectMs: number | null } | null
  mem: { free: number; total: number }
  uptimeSec: number
  firstSeen: number
  lastSeen: number
  note: string | null
  /** Process identity of the agent that currently owns this device. */
  instanceId: string | null
  /** Agent process start time (ms). Newer wins; older zombies cannot preempt. */
  instanceStartedAt: number
  /** Last state the agent reported (starting/connected/error/replaced/standby). */
  agentState: string
  lastError: string | null
}

type Command = {
  id: string
  device: string
  action: string
  args: Record<string, unknown>
  status: 'pending' | 'running' | 'done' | 'failed'
  createdAt: number
  startedAt: number | null
  finishedAt: number | null
  result: unknown
}

type Event = { at: number; device: string | null; kind: string; message: string }

const devices = new Map<string, Device>()
const commands = new Map<string, Command>()
const events: Event[] = []

function loadState() {
  for (const d of loadJson<Device[]>(join(DATA, 'devices.json'), [])) {
    devices.set(d.id, {
      ...d,
      protocolVersion: d.protocolVersion ?? 1,
      controlPort: d.controlPort ?? null,
      instanceId: d.instanceId ?? null,
      instanceStartedAt: d.instanceStartedAt ?? 0,
      agentState: d.agentState ?? 'unknown',
      lastError: d.lastError ?? null
    })
  }
  for (const c of loadJson<Command[]>(join(DATA, 'commands.json'), [])) commands.set(c.id, c)
  events.push(...loadJson<Event[]>(join(DATA, 'events.json'), []))
}
function persist() {
  saveJson(join(DATA, 'devices.json'), [...devices.values()])
  saveJson(join(DATA, 'commands.json'), [...commands.values()].slice(-500))
  saveJson(join(DATA, 'events.json'), events.slice(-500))
}
loadState()

function logEvent(device: string | null, kind: string, message: string) {
  events.push({ at: Date.now(), device, kind, message })
  if (events.length > 500) events.splice(0, events.length - 500)
}

// ---------- http helpers ----------
function send(res: ServerResponse, status: number, body: unknown, type = 'application/json; charset=utf-8') {
  const buf = Buffer.isBuffer(body) ? body : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body), 'utf8')
  res.writeHead(status, {
    'content-type': type,
    'content-length': buf.length,
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'content-type,x-mesh-token,x-mesh-protocol,x-mesh-request-id,x-mesh-instance,last-event-id',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
    'x-mesh-protocol': String(MESH_PROTOCOL)
  })
  res.end(buf)
}

function fail(res: ServerResponse, code: MeshErrorCode, message: string, status?: number) {
  return send(res, status ?? ERROR_STATUS[code] ?? 500, { error: message, code })
}

function readBody(req: IncomingMessage, limit = LIMITS.ingestBytes): Promise<string> {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks: Buffer[] = []
    req.on('data', (c: Buffer) => {
      size += c.length
      if (size > limit) {
        reject(Object.assign(new Error('payload too large'), { code: 'PAYLOAD_TOO_LARGE' }))
        req.resume()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function authed(req: IncomingMessage, url: URL): boolean {
  const h = req.headers['x-mesh-token']
  if (typeof h === 'string' && h === TOKEN) return true
  const auth = req.headers.authorization
  if (auth === `Bearer ${TOKEN}`) return true
  return url.searchParams.get('t') === TOKEN
}

function instanceHeader(req: IncomingMessage): string | null {
  const h = req.headers[HEADER_INSTANCE]
  return typeof h === 'string' && h.trim() ? h.trim() : null
}

/** A stale agent tried to write while another instance owns the device. */
function replaced(res: ServerResponse, d: Device) {
  return send(res, 409, {
    error: 'another agent instance owns this device',
    code: 'AGENT_REPLACED',
    activeInstanceId: d.instanceId,
    activeStartedAt: d.instanceStartedAt
  })
}

function deviceView(d: Device) {
  return { ...d, online: Date.now() - d.lastSeen < OFFLINE_AFTER_MS, ageMs: Date.now() - d.lastSeen }
}

// ---------- config store ----------
function configNames(): string[] {
  if (!existsSync(CONFIGS)) return []
  return readdirSync(CONFIGS).filter(n => statSync(join(CONFIGS, n)).isDirectory())
}
function configVersions(name: string): string[] {
  const dir = join(CONFIGS, name)
  if (!existsSync(dir)) return []
  return readdirSync(dir).filter(f => f !== 'latest').sort().reverse()
}
function latestPath(name: string) { return join(CONFIGS, name, 'latest') }

// ---------- path classification (direct vs relay) ----------
// The badge used to guess "direct" from an RTT threshold, which mislabels a
// congested LAN as a relay and a fast DERP as direct. Classify the actual
// source address instead, and ask Tailscale whether the peer has a direct
// endpoint (`CurAddr`) or is only reachable through a DERP relay.
// Same contract as cindy's directPath.ts: the answer is the path the bytes
// really take, with a reason when it is not direct.

type PathKind = 'direct' | 'relay' | 'local' | 'wan' | 'unknown'

function normalizeIp(ip: string): string {
  return ip.replace(/^::ffff:/, '')
}

function ipClass(ip: string): 'loopback' | 'lan' | 'tailscale' | 'wan' {
  if (ip === '127.0.0.1' || ip === '::1') return 'loopback'
  const p = ip.split('.').map(Number)
  if (p.length !== 4 || p.some(n => !Number.isFinite(n))) return 'wan'
  if (p[0] === 100 && p[1] >= 64 && p[1] <= 127) return 'tailscale'
  if (p[0] === 10) return 'lan'
  if (p[0] === 192 && p[1] === 168) return 'lan'
  if (p[0] === 172 && p[1] >= 16 && p[1] <= 31) return 'lan'
  return 'wan'
}

const TS_CANDIDATES = process.platform === 'win32'
  ? [join(process.env.ProgramFiles || 'C:\\Program Files', 'Tailscale', 'tailscale.exe'), 'tailscale.exe']
  : ['tailscale', '/Applications/Tailscale.app/Contents/MacOS/Tailscale']

let tsCache: { at: number; peers: Map<string, { direct: boolean; relay: string | null; host: string }> } | null = null

function readTailscale(): Promise<Map<string, { direct: boolean; relay: string | null; host: string }>> {
  if (tsCache && Date.now() - tsCache.at < 5000) return Promise.resolve(tsCache.peers)
  const empty = new Map<string, { direct: boolean; relay: string | null; host: string }>()
  return new Promise(resolve => {
    const tryNext = (i: number) => {
      if (i >= TS_CANDIDATES.length) {
        tsCache = { at: Date.now(), peers: empty }
        return resolve(empty)
      }
      execFile(TS_CANDIDATES[i], ['status', '--json'], { timeout: 2000, windowsHide: true }, (err, stdout) => {
        if (err || !stdout) return tryNext(i + 1)
        const peers = new Map<string, { direct: boolean; relay: string | null; host: string }>()
        try {
          const status = JSON.parse(stdout) as { Peer?: Record<string, any>; Self?: any }
          for (const peer of Object.values(status.Peer ?? {})) {
            const info = {
              direct: typeof peer.CurAddr === 'string' && peer.CurAddr.length > 0,
              relay: typeof peer.Relay === 'string' && peer.Relay ? peer.Relay : null,
              host: String(peer.HostName ?? '')
            }
            for (const ip of peer.TailscaleIPs ?? []) peers.set(normalizeIp(String(ip)), info)
          }
          if (status.Self) {
            const self = { direct: true, relay: null as string | null, host: String(status.Self.HostName ?? '') }
            for (const ip of status.Self.TailscaleIPs ?? []) peers.set(normalizeIp(String(ip)), self)
          }
        } catch { /* keep empty */ }
        tsCache = { at: Date.now(), peers }
        resolve(peers)
      })
    }
    tryNext(0)
  })
}

async function classifyPath(req: IncomingMessage) {
  const peer = normalizeIp(req.socket.remoteAddress || '')
  const cls = ipClass(peer)
  if (cls === 'loopback') return { peer, ipClass: cls, path: 'local' as PathKind, reason: 'loopback' }
  if (cls === 'lan') return { peer, ipClass: cls, path: 'direct' as PathKind, reason: 'lan' }
  if (cls === 'wan') return { peer, ipClass: cls, path: 'wan' as PathKind, reason: 'public-route' }

  const peers = await readTailscale()
  const ts = peers.get(peer)
  if (!ts) return { peer, ipClass: cls, path: 'unknown' as PathKind, reason: 'tailscale-status-unavailable' }
  if (ts.direct) return { peer, ipClass: cls, path: 'direct' as PathKind, reason: 'tailscale-direct', tailscale: ts }
  if (ts.relay) return { peer, ipClass: cls, path: 'relay' as PathKind, reason: 'tailscale-derp', tailscale: ts }
  return { peer, ipClass: cls, path: 'unknown' as PathKind, reason: 'tailscale-no-endpoint', tailscale: ts }
}

// ---------- phone -> device control proxy ----------
// The phone never reaches opencode directly. It calls /api/oc/<device>/... and
// the hub forwards only the allowlisted methods to that device's agent, which
// enforces the same table again.

const CONTROL_ROUTES: Array<{ method: string; re: RegExp }> = [
  { method: 'GET', re: /^health$/ },
  { method: 'GET', re: /^status$/ },
  { method: 'GET', re: /^sessions$/ },
  { method: 'GET', re: /^session\/[^/]+$/ },
  { method: 'GET', re: /^session\/[^/]+\/messages$/ },
  { method: 'GET', re: /^session\/[^/]+\/inbox$/ },
  { method: 'GET', re: /^session\/[^/]+\/permissions$/ },
  { method: 'POST', re: /^session\/[^/]+\/prompt$/ },
  { method: 'POST', re: /^session\/[^/]+\/interrupt$/ },
  { method: 'POST', re: /^session\/[^/]+\/permissions\/[^/]+\/reply$/ },
  { method: 'GET', re: /^models$/ },
  { method: 'GET', re: /^events$/ }
]

function allowedControl(method: string, rest: string): boolean {
  return CONTROL_ROUTES.some(r => r.method === method && r.re.test(rest))
}

const controlBase = new Map<string, { base: string; at: number }>()

function ipRank(ip: string): number {
  const cls = ipClass(ip)
  if (cls === 'lan') return 0
  if (cls === 'tailscale') return 1
  return 2
}

function candidateBases(d: Device): string[] {
  const port = d.controlPort || 18998
  return [...d.ips].sort((a, b) => ipRank(a) - ipRank(b)).map(ip => `http://${ip}:${port}`)
}

async function controlFetch(d: Device, rest: string, init: RequestInit, timeoutMs: number): Promise<{ res: Response; base: string }> {
  const cached = controlBase.get(d.id)
  const bases = cached && Date.now() - cached.at < 60_000 ? [cached.base, ...candidateBases(d).filter(b => b !== cached.base)] : candidateBases(d)
  let lastErr: Error | null = null
  for (const base of bases) {
    const controller = new AbortController()
    const timer = timeoutMs ? setTimeout(() => controller.abort(), timeoutMs) : null
    try {
      const res = await fetch(base + '/' + rest, { ...init, signal: controller.signal })
      if (timer) clearTimeout(timer)
      controlBase.set(d.id, { base, at: Date.now() })
      return { res, base }
    } catch (err) {
      if (timer) clearTimeout(timer)
      lastErr = err as Error
      controlBase.delete(d.id)
    }
  }
  throw lastErr ?? new Error('no reachable control endpoint')
}

// Phone SSE streams are registered per device so a takeover can tell them
// *why* the stream is about to end, instead of letting them guess from a bare
// TCP close. The client reconnects and picks up the new instance.
type DeviceStream = { res: ServerResponse; stream: Readable }
const deviceStreams = new Map<string, Set<DeviceStream>>()

function trackDeviceStream(deviceId: string, entry: DeviceStream) {
  let set = deviceStreams.get(deviceId)
  if (!set) {
    set = new Set()
    deviceStreams.set(deviceId, set)
  }
  set.add(entry)
}

function untrackDeviceStream(deviceId: string, entry: DeviceStream) {
  const set = deviceStreams.get(deviceId)
  if (!set) return
  set.delete(entry)
  if (set.size === 0) deviceStreams.delete(deviceId)
}

function notifyAgentReplaced(deviceId: string, prev: Device | undefined, next: Device) {
  const set = deviceStreams.get(deviceId)
  if (!set || set.size === 0) return
  const payload = JSON.stringify({
    at: Date.now(),
    activeInstanceId: next.instanceId,
    activeStartedAt: next.instanceStartedAt,
    previousInstanceId: prev?.instanceId ?? null
  })
  for (const entry of [...set]) {
    try {
      entry.res.write(`event: mesh.agent-replaced\ndata: ${payload}\n\n`)
    } catch { /* already gone */ }
    try {
      entry.stream.unpipe(entry.res)
      entry.stream.destroy()
    } catch { /* already gone */ }
    try {
      entry.res.end()
    } catch { /* already gone */ }
  }
  deviceStreams.delete(deviceId)
}

async function proxyControl(req: IncomingMessage, res: ServerResponse, d: Device, rest: string, url: URL) {
  if (!allowedControl(req.method || 'GET', rest)) {
    return fail(res, 'CHANNEL_NOT_ALLOWED', `not allowed: ${req.method} ${rest}`)
  }
  if (!d.controlPort) return fail(res, 'UPSTREAM_UNAVAILABLE', 'device agent has no control plane (upgrade the agent)')

  const headers: Record<string, string> = {
    [HEADER_TOKEN]: TOKEN,
    [HEADER_PROTOCOL]: String(MESH_PROTOCOL)
  }
  const rid = req.headers[HEADER_REQUEST_ID]
  if (typeof rid === 'string') headers[HEADER_REQUEST_ID] = rid

  if (rest === 'events') {
    const since = url.searchParams.get('since')
    const target = since ? `events?since=${encodeURIComponent(since)}` : 'events'
    let upstream: Response
    try {
      upstream = (await controlFetch(d, target, { headers }, SSE_CONNECT_TIMEOUT_MS)).res
    } catch (err) {
      return fail(res, 'UPSTREAM_UNAVAILABLE', (err as Error).message)
    }
    if (!upstream.ok || !upstream.body) return fail(res, 'UPSTREAM_UNAVAILABLE', `event stream -> ${upstream.status}`)
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
      'access-control-allow-origin': '*'
    })
    const stream = Readable.fromWeb(upstream.body as any)
    const entry: DeviceStream = { res, stream }
    trackDeviceStream(d.id, entry)
    stream.pipe(res)
    req.on('close', () => {
      untrackDeviceStream(d.id, entry)
      stream.destroy()
      void upstream.body?.cancel().catch(() => {})
    })
    stream.on('close', () => untrackDeviceStream(d.id, entry))
    return
  }

  let body: Buffer | undefined
  if (req.method === 'POST') {
    try {
      const raw = await readBody(req, LIMITS.controlBytes)
      body = Buffer.from(raw, 'utf8')
      headers['content-type'] = 'application/json'
    } catch (err) {
      return fail(res, 'PAYLOAD_TOO_LARGE', 'payload too large')
    }
  }

  let result: Response
  try {
    result = (await controlFetch(d, rest, { method: req.method, headers, body }, CONTROL_TIMEOUT_MS)).res
  } catch (err) {
    const e = err as Error
    return fail(res, e.name === 'AbortError' ? 'TIMEOUT' : 'UPSTREAM_UNAVAILABLE', e.message)
  }

  const text = await result.text()
  const extra: Record<string, string> = {}
  const replayed = result.headers.get('x-mesh-replayed')
  if (replayed) extra['x-mesh-replayed'] = replayed
  res.writeHead(result.status, {
    'content-type': result.headers.get('content-type') || 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
    ...extra
  })
  res.end(text)
}

// ---------- routes ----------
const server = createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`)
  const path = url.pathname

  if (req.method === 'OPTIONS') return send(res, 204, '')

  try {
    if (path === '/api/health') return send(res, 200, { ok: true, version: VERSION, protocol: MESH_PROTOCOL, devices: devices.size })

    if (path === '/phone' || path.startsWith('/phone/')) {
      if (!authed(req, url)) return send(res, 401, { error: 'unauthorized' })
      const name = decodeURIComponent(path.slice('/phone/'.length)) || 'index'
      const file = join(CONFIGS, name, 'latest')
      if (!existsSync(file)) return send(res, 404, `no such config: ${name}`, 'text/plain; charset=utf-8')
      return send(res, 200, readFileSync(file), 'text/yaml; charset=utf-8')
    }

    if (path.startsWith('/api/') && !authed(req, url)) return send(res, 401, { error: 'unauthorized' })

    if (req.method === 'GET' && path === '/api/path') {
      return send(res, 200, await classifyPath(req))
    }

    if (path.startsWith('/api/oc/')) {
      const restPath = path.slice('/api/oc/'.length)
      const slash = restPath.indexOf('/')
      if (slash < 0) return fail(res, 'BAD_REQUEST', 'expected /api/oc/<device>/<method>')
      const deviceId = decodeURIComponent(restPath.slice(0, slash))
      const rest = restPath.slice(slash + 1)
      const d = devices.get(deviceId)
      if (!d) return fail(res, 'NOT_FOUND', `no such device: ${deviceId}`)
      if (Date.now() - d.lastSeen >= OFFLINE_AFTER_MS) return fail(res, 'DEVICE_OFFLINE', `${deviceId} is offline`)
      return await proxyControl(req, res, d, rest, url)
    }

    if (req.method === 'POST' && path === '/api/ingest') {
      const peerProtocol = Number(req.headers[HEADER_PROTOCOL] ?? '1')
      if (!Number.isFinite(peerProtocol) || peerProtocol < MESH_PROTOCOL_MIN) {
        return send(res, 426, {
          error: 'agent protocol too old', code: 'VERSION_MISMATCH',
          hubProtocol: MESH_PROTOCOL, peerProtocol
        })
      }
      if (peerProtocol > MESH_PROTOCOL) {
        return send(res, 426, {
          error: 'agent protocol newer than hub', code: 'VERSION_MISMATCH',
          hubProtocol: MESH_PROTOCOL, peerProtocol
        })
      }

      const body = JSON.parse(await readBody(req, LIMITS.ingestBytes)) as Partial<Device> & {
        id?: string
        instanceId?: string
        instanceStartedAt?: number
        agentState?: string
        lastError?: string | null
      }
      const id = String(body.id || '').trim()
      if (!id) return fail(res, 'BAD_REQUEST', 'id required')
      if (!devices.has(id) && devices.size >= LIMITS.maxDevices) return fail(res, 'CONGESTED', 'device limit reached')
      const incomingInstanceId = String(body.instanceId || req.headers[HEADER_INSTANCE] || '').trim() || null
      const incomingStartedAt = Number(body.instanceStartedAt || 0) || 0
      const now = Date.now()
      const prev = devices.get(id)
      const activeOnline = !!prev && now - prev.lastSeen < OFFLINE_AFTER_MS

      // Single-writer rule: a newer process may take over; an older zombie
      // (or a legacy agent without an instance id) may not overwrite a live
      // managed instance. When the current instance is offline, anyone may
      // claim the device.
      let takeover = false
      if (activeOnline && prev) {
        if (prev.instanceId) {
          if (!incomingInstanceId) return replaced(res, prev)
          if (prev.instanceId !== incomingInstanceId) {
            if (incomingStartedAt > prev.instanceStartedAt) takeover = true
            else return replaced(res, prev)
          }
        } else if (incomingInstanceId) {
          takeover = true
        }
      }
      const instanceChanged = !!prev && prev.instanceId !== incomingInstanceId
      if (!activeOnline && instanceChanged) takeover = true

      const next: Device = {
        id,
        hostname: String(body.hostname || id),
        platform: String(body.platform || ''),
        arch: String(body.arch || ''),
        agentVersion: String(body.agentVersion || ''),
        protocolVersion: Number(body.protocolVersion || peerProtocol || 1),
        controlPort: Number(body.control?.port || 0) || null,
        ips: Array.isArray(body.ips) ? body.ips.slice(0, 8).map(String) : [],
        gateway: body.gateway ?? null,
        proxy: (body.proxy as Device['proxy']) ?? null,
        ports: (body.ports as Device['ports']) ?? {},
        opencode: (body.opencode as Device['opencode']) ?? null,
        latency: (body.latency as Device['latency']) ?? null,
        mem: (body.mem as Device['mem']) ?? { free: 0, total: 0 },
        uptimeSec: Number(body.uptimeSec || 0),
        firstSeen: prev?.firstSeen ?? now,
        lastSeen: now,
        note: (body.note as string) ?? prev?.note ?? null,
        instanceId: incomingInstanceId,
        instanceStartedAt: incomingStartedAt,
        agentState: String(body.agentState || 'unknown'),
        lastError: body.lastError ? String(body.lastError) : null
      }
      devices.set(id, next)
      if (takeover) {
        controlBase.delete(id)
        // A command claimed by the old instance may still be running there.
        // Its result will be rejected by the instance check, so settle it now
        // instead of leaving the UI waiting forever. Pending commands stay
        // queued for the new instance.
        for (const c of commands.values()) {
          if (c.device === id && c.status === 'running') {
            c.status = 'failed'
            c.finishedAt = now
            c.result = { error: 'agent replaced before the command finished' }
          }
        }
        notifyAgentReplaced(id, prev, next)
        logEvent(id, 'agent', `实例接管 ${prev?.instanceId ?? 'legacy'} -> ${incomingInstanceId ?? 'legacy'}`)
      }
      if (!prev) logEvent(id, 'online', `${next.hostname} 首次上线`)
      else if (now - prev.lastSeen > OFFLINE_AFTER_MS) logEvent(id, 'online', `${next.hostname} 重新上线`)
      persist()
      return send(res, 200, { ok: true, serverTime: now })
    }

    if (req.method === 'GET' && path === '/api/devices') {
      const list = [...devices.values()].map(deviceView).sort((a, b) => a.hostname.localeCompare(b.hostname))
      return send(res, 200, { devices: list, now: Date.now(), offlineAfterMs: OFFLINE_AFTER_MS })
    }

    if (req.method === 'GET' && path.startsWith('/api/device/')) {
      const id = decodeURIComponent(path.slice('/api/device/'.length))
      const d = devices.get(id)
      return d ? send(res, 200, deviceView(d)) : send(res, 404, { error: 'no such device' })
    }

    if (req.method === 'GET' && path === '/api/events') {
      const limit = Math.min(Number(url.searchParams.get('limit') || 80), 300)
      return send(res, 200, { events: events.slice(-limit).reverse() })
    }

    if (req.method === 'POST' && path === '/api/commands') {
      const body = JSON.parse(await readBody(req, LIMITS.commandBytes)) as { device?: string; action?: string; args?: Record<string, unknown> }
      if (!body.device || !body.action) return fail(res, 'BAD_REQUEST', 'device and action required')
      const pending = [...commands.values()].filter(c => c.device === body.device && c.status === 'pending').length
      if (pending >= LIMITS.pendingCommandsPerDevice) {
        return fail(res, 'TOO_MANY_COMMANDS', `${body.device} already has ${pending} pending commands`)
      }
      const cmd: Command = {
        id: randomUUID(), device: body.device, action: body.action, args: body.args || {},
        status: 'pending', createdAt: Date.now(), startedAt: null, finishedAt: null, result: null
      }
      commands.set(cmd.id, cmd)
      logEvent(cmd.device, 'command', `排队 ${cmd.action}`)
      persist()
      return send(res, 200, cmd)
    }

    if (req.method === 'GET' && path === '/api/commands') {
      const device = url.searchParams.get('device')
      const d = device ? devices.get(device) : undefined
      if (d?.instanceId && instanceHeader(req) !== d.instanceId) return replaced(res, d)
      const status = url.searchParams.get('status') || 'pending'
      let list = [...commands.values()]
      if (device) list = list.filter(c => c.device === device)
      if (status !== 'all') list = list.filter(c => c.status === status)
      return send(res, 200, { commands: list.sort((a, b) => a.createdAt - b.createdAt).slice(0, 50) })
    }

    if (req.method === 'POST' && path === '/api/commands/result') {
      const body = JSON.parse(await readBody(req, LIMITS.commandResultBytes)) as { id?: string; ok?: boolean; result?: unknown }
      const cmd = body.id ? commands.get(body.id) : undefined
      if (!cmd) return fail(res, 'NOT_FOUND', 'no such command')
      const d = cmd.device ? devices.get(cmd.device) : undefined
      if (d?.instanceId && instanceHeader(req) !== d.instanceId) return replaced(res, d)
      cmd.status = body.ok ? 'done' : 'failed'
      cmd.finishedAt = Date.now()
      cmd.result = body.result ?? null
      logEvent(cmd.device, 'command', `${cmd.action} ${cmd.status}`)
      persist()
      return send(res, 200, { ok: true })
    }

    if (req.method === 'POST' && path.startsWith('/api/commands/') && path.endsWith('/claim')) {
      const id = path.slice('/api/commands/'.length, -'/claim'.length)
      const cmd = commands.get(id)
      if (!cmd) return send(res, 404, { error: 'no such command' })
      const d = cmd.device ? devices.get(cmd.device) : undefined
      if (d?.instanceId && instanceHeader(req) !== d.instanceId) return replaced(res, d)
      cmd.status = 'running'
      cmd.startedAt = Date.now()
      persist()
      return send(res, 200, cmd)
    }

    if (req.method === 'GET' && path === '/api/configs') {
      return send(res, 200, {
        configs: configNames().map(n => ({ name: n, versions: configVersions(n) }))
      })
    }

    if (path.startsWith('/api/config/')) {
      const name = decodeURIComponent(path.slice('/api/config/'.length))
      if (!/^[a-zA-Z0-9._-]+$/.test(name)) return send(res, 400, { error: 'bad name' })
      if (req.method === 'GET') {
        const file = latestPath(name)
        if (!existsSync(file)) return send(res, 404, { error: 'no such config' })
        return send(res, 200, { name, versions: configVersions(name), content: readFileSync(file, 'utf8') })
      }
      if (req.method === 'POST') {
        const content = await readBody(req, LIMITS.configBytes)
        if (!content.trim()) return fail(res, 'BAD_REQUEST', 'empty body')
        const dir = join(CONFIGS, name)
        if (!existsSync(dir)) mkdirSync(dir, { recursive: true })
        const stamp = new Date().toISOString().replace(/[:.]/g, '-')
        writeFileSync(join(dir, stamp), content, 'utf8')
        writeFileSync(latestPath(name), content, 'utf8')
        logEvent(null, 'config', `${name} 新版本 ${stamp}`)
        persist()
        return send(res, 200, { ok: true, name, version: stamp })
      }
    }

    // static UI
    if (req.method === 'GET') {
      const rel = path === '/' ? 'index.html' : path.replace(/^\/+/, '')
      const file = join(WEB, basename(rel))
      if (existsSync(file) && statSync(file).isFile()) {
        const ext = extname(file).toLowerCase()
        const mime: Record<string, string> = {
          '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
          '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.json': 'application/json'
        }
        return send(res, 200, readFileSync(file), mime[ext] || 'application/octet-stream')
      }
    }

    return send(res, 404, { error: 'not found', path })
  } catch (err) {
    const e = err as Error & { code?: string }
    if (e.code === 'PAYLOAD_TOO_LARGE') return fail(res, 'PAYLOAD_TOO_LARGE', 'payload too large')
    console.error('[hub]', e.message)
    return fail(res, 'INTERNAL', e.message)
  }
})

server.on('error', (err: NodeJS.ErrnoException) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`[mesh] port ${PORT} is already in use; set MESH_PORT to a free port`)
  } else {
    console.error(`[mesh] ${err.message}`)
  }
  process.exit(1)
})

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[mesh] hub v${VERSION} on http://0.0.0.0:${PORT}`)
  console.log(`[mesh] token: ${TOKEN}`)
  console.log(`[mesh] ui:    http://127.0.0.1:${PORT}/`)
})

process.on('SIGINT', () => { persist(); process.exit(0) })
process.on('SIGTERM', () => { persist(); process.exit(0) })
