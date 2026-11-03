// mesh agent - reports device state to the hub and executes whitelisted actions.
// Zero dependencies. Runs directly on Node >= 22.6 (node agent/agent.ts).
//
//   MESH_HUB=http://192.168.137.1:18994
//   MESH_TOKEN=<token from hub>
//   MESH_DEVICE=laptop            (default: hostname, lowercased)
import os from 'node:os'
import net from 'node:net'
import { randomUUID } from 'node:crypto'
import { join } from 'node:path'
import { execFile } from 'node:child_process'
import { HEADER_INSTANCE, HEADER_PROTOCOL, MESH_PROTOCOL } from '../shared/protocol.ts'
import { startControl } from './control.ts'
import { resolveTarget } from './opencode.ts'

const HUB = (process.env.MESH_HUB || 'http://127.0.0.1:18994').replace(/\/+$/, '')
const TOKEN = process.env.MESH_TOKEN || ''
const DEVICE = (process.env.MESH_DEVICE || os.hostname()).toLowerCase().replace(/[^a-z0-9._-]/g, '-')
const AGENT_VERSION = '0.3.0'
const PUSH_MS = 5_000
const SLOW_MS = 30_000
const CONTROL_PORT_BASE = Number(process.env.MESH_CONTROL_PORT || 18998)
const CONTROL_HOST = process.env.MESH_CONTROL_BIND || '0.0.0.0'
const RETRY_MIN_MS = 1_000
const RETRY_MAX_MS = 30_000
const RECLAIM_MIN_MS = Math.max(0, Number(process.env.MESH_RECLAIM_MS || 0) || 0)
const RECLAIM_MAX_MS = 10 * 60_000
const RECLAIM_STABLE_MS = 60_000

// One identity per process, not per device. A restarted agent gets a new id
// and a newer start time, so it can take over; a leaked older process can
// never steal the device back from it.
const INSTANCE_ID = randomUUID()
const INSTANCE_STARTED_AT = Date.now()
let controlPort = CONTROL_PORT_BASE

type AgentState = 'starting' | 'connected' | 'error' | 'replaced' | 'standby'
const status: {
  state: AgentState
  lastError: string | null
  lastIngestAt: number
  connectedAt: number | null
} = {
  state: 'starting',
  lastError: null,
  lastIngestAt: 0,
  connectedAt: null
}

if (!TOKEN) {
  console.error('[agent] MESH_TOKEN is required')
  process.exit(1)
}

const isWin = process.platform === 'win32'
const isMac = process.platform === 'darwin'
// NOTE: never spawn bare "powershell.exe" on Windows - a PowerShell7 shim
// (C:\Program Files\PowerShell7Shim) can win the PATH lookup and fail with
// "access denied" from a non-interactive service session.
const WIN_PS = join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')

function exec(cmd: string, args: string[], timeout = 8000): Promise<{ code: number; out: string; err: string }> {
  return new Promise(resolve => {
    execFile(cmd, args, { timeout, windowsHide: true }, (err, stdout, stderr) => {
      resolve({ code: err ? (typeof (err as any).code === 'number' ? (err as any).code : 1) : 0, out: String(stdout || ''), err: String(stderr || '') })
    })
  })
}

function runPs(script: string): Promise<{ code: number; out: string; err: string }> {
  const b64 = Buffer.from(script, 'utf16le').toString('base64')
  return exec(WIN_PS, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', b64], 15000)
}

// ---------- probes ----------
function tcpProbe(host: string, port: number, timeout = 1500): Promise<number | null> {
  return new Promise(resolve => {
    const started = Date.now()
    const socket = net.connect({ host, port })
    const done = (ok: boolean) => {
      socket.destroy()
      resolve(ok ? Date.now() - started : null)
    }
    socket.setTimeout(timeout)
    socket.once('connect', () => done(true))
    socket.once('timeout', () => done(false))
    socket.once('error', () => done(false))
  })
}

function connectProbe(host: string, port: number, target = 'www.google.com:443', timeout = 6000): Promise<number | null> {
  return new Promise(resolve => {
    const started = Date.now()
    const socket = net.connect({ host, port })
    let settled = false
    const finish = (ok: boolean) => {
      if (settled) return
      settled = true
      socket.destroy()
      resolve(ok ? Date.now() - started : null)
    }
    socket.setTimeout(timeout)
    socket.once('connect', () => {
      socket.write(`CONNECT ${target} HTTP/1.1\r\nHost: ${target}\r\n\r\n`)
    })
    let buf = ''
    socket.on('data', d => {
      buf += d.toString('latin1')
      if (buf.startsWith('HTTP/1.1 200') || buf.startsWith('HTTP/1.0 200')) finish(true)
      else if (buf.includes('\r\n\r\n')) finish(false)
    })
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
  })
}

async function readSystemProxy(): Promise<{ enabled: boolean; server: string | null }> {
  try {
    if (isWin) {
      const r = await runPs([
        "$k='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'",
        '$p=Get-ItemProperty $k',
        '"{0}|{1}" -f $p.ProxyEnable,$p.ProxyServer'
      ].join('; '))
      const [en, server] = r.out.trim().split('|')
      return { enabled: en === '1', server: server || null }
    }
    if (isMac) {
      const r = await exec('scutil', ['--proxy'])
      const https = /HTTPSEnable\s*:\s*1[\s\S]*?HTTPSProxy\s*:\s*(\S+)[\s\S]*?HTTPSPort\s*:\s*(\d+)/.exec(r.out)
      if (https) return { enabled: true, server: `${https[1]}:${https[2]}` }
      return { enabled: false, server: null }
    }
    const env = process.env.HTTPS_PROXY || process.env.https_proxy || null
    return { enabled: !!env, server: env ? env.replace(/^https?:\/\//, '') : null }
  } catch {
    return { enabled: false, server: null }
  }
}

async function readGateway(): Promise<string | null> {
  try {
    if (isWin) {
      const r = await runPs("(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1).NextHop")
      const line = r.out.trim().split(/\r?\n/).pop() || ''
      return /^\d+\.\d+\.\d+\.\d+$/.test(line) ? line : null
    }
    if (isMac) {
      const r = await exec('route', ['-n', 'get', 'default'])
      const m = /gateway:\s*(\S+)/.exec(r.out)
      return m ? m[1] : null
    }
    const r = await exec('sh', ['-c', "ip route | awk '/default/ {print $3; exit}'"])
    return r.out.trim() || null
  } catch {
    return null
  }
}

function ips(): string[] {
  const out: string[] = []
  for (const list of Object.values(os.networkInterfaces())) {
    for (const i of list || []) {
      if (i.family === 'IPv4' && !i.internal) out.push(i.address)
    }
  }
  return [...new Set(out)]
}

// ---------- state ----------
let cachedGateway: string | null = null
let cachedProxy: { enabled: boolean; server: string | null } = { enabled: false, server: null }
let cachedLatency: { target: string; tcpMs: number | null; connectMs: number | null } | null = null
let slowAt = 0

async function collect() {
  const now = Date.now()
  if (now - slowAt > SLOW_MS) {
    slowAt = now
    cachedGateway = await readGateway()
    cachedProxy = await readSystemProxy()

    const ports: Record<string, boolean> = {}
    for (const p of [7890, 7897, 1082, 4096, 4097]) {
      ports[String(p)] = (await tcpProbe('127.0.0.1', p, 600)) !== null
    }
    ;(globalThis as any).__ports = ports

    const opencodePort = ports['4096'] ? 4096 : ports['4097'] ? 4097 : null
    ;(globalThis as any).__opencode = opencodePort ? { port: opencodePort } : null

    if (cachedProxy.enabled && cachedProxy.server) {
      const [host, portStr] = cachedProxy.server.split(':')
      const port = Number(portStr || 80)
      const tcpMs = await tcpProbe(host, port, 2000)
      const connectMs = tcpMs === null ? null : await connectProbe(host, port).catch(() => null)
      cachedLatency = { target: cachedProxy.server, tcpMs, connectMs }
    } else {
      cachedLatency = null
    }
  }

  const mem = { free: Math.round(os.freemem() / 1048576), total: Math.round(os.totalmem() / 1048576) }
  return {
    id: DEVICE,
    hostname: os.hostname(),
    platform: `${process.platform} ${os.release()}`,
    arch: process.arch,
    agentVersion: AGENT_VERSION,
    protocolVersion: MESH_PROTOCOL,
    instanceId: INSTANCE_ID,
    instanceStartedAt: INSTANCE_STARTED_AT,
    agentState: status.state,
    lastError: status.lastError,
    control: { port: controlPort },
    ips: ips(),
    gateway: cachedGateway,
    proxy: cachedProxy,
    ports: (globalThis as any).__ports || {},
    opencode: (globalThis as any).__opencode || null,
    latency: cachedLatency,
    mem,
    uptimeSec: Math.round(os.uptime()),
    note: null
  }
}

// ---------- actions ----------
const RESTARTABLE = new Set(['WProxy', 'WProxyHysteria', 'PcProxyFailover', 'PcLanGateway', 'PhoneConfigHttp'])

async function setSystemProxy(server: string) {
  if (!isWin) return { ok: false, error: 'windows only' }
  const script = [
    "$k='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'",
    `Set-ItemProperty -LiteralPath $k -Name ProxyServer -Value '${server.replace(/'/g, "''")}' -Type String`,
    'Set-ItemProperty -LiteralPath $k -Name ProxyEnable -Value 1 -Type DWord',
    "$sig='[DllImport(\"wininet.dll\",SetLastError=true)]public static extern bool InternetSetOption(IntPtr h,int o,IntPtr b,int l);'",
    '$t=Add-Type -MemberDefinition $sig -Name W -Namespace Mesh -PassThru',
    'try{[void]$t::InternetSetOption([IntPtr]::Zero,39,[IntPtr]::Zero,0);[void]$t::InternetSetOption([IntPtr]::Zero,37,[IntPtr]::Zero,0)}catch{}',
    "'ok'"
  ].join('; ')
  const r = await runPs(script)
  return { ok: r.code === 0, out: r.out.trim(), err: r.err.trim() }
}

async function clearSystemProxy() {
  if (!isWin) return { ok: false, error: 'windows only' }
  const script = [
    "$k='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'",
    'Set-ItemProperty -LiteralPath $k -Name ProxyEnable -Value 0 -Type DWord',
    "$sig='[DllImport(\"wininet.dll\",SetLastError=true)]public static extern bool InternetSetOption(IntPtr h,int o,IntPtr b,int l);'",
    '$t=Add-Type -MemberDefinition $sig -Name W -Namespace Mesh -PassThru',
    'try{[void]$t::InternetSetOption([IntPtr]::Zero,39,[IntPtr]::Zero,0);[void]$t::InternetSetOption([IntPtr]::Zero,37,[IntPtr]::Zero,0)}catch{}',
    "'ok'"
  ].join('; ')
  const r = await runPs(script)
  return { ok: r.code === 0, out: r.out.trim(), err: r.err.trim() }
}

async function restartService(name: string) {
  if (!isWin) return { ok: false, error: 'windows only' }
  if (!RESTARTABLE.has(name)) return { ok: false, error: `not whitelisted: ${name}` }
  await exec('schtasks.exe', ['/End', '/TN', name], 8000)
  await new Promise(r => setTimeout(r, 1500))
  const r = await exec('schtasks.exe', ['/Run', '/TN', name], 8000)
  return { ok: r.code === 0, out: r.out.trim(), err: r.err.trim() }
}

async function execute(cmd: { id: string; action: string; args: Record<string, unknown> }) {
  switch (cmd.action) {
    case 'set-proxy':
      return setSystemProxy(String(cmd.args.server || ''))
    case 'clear-proxy':
      return clearSystemProxy()
    case 'restart-service':
      return restartService(String(cmd.args.name || ''))
    case 'probe':
      slowAt = 0
      return { ok: true, note: 'probe queued' }
    default:
      return { ok: false, error: `unknown action: ${cmd.action}` }
  }
}

// ---------- main loop ----------
function headers(extra: Record<string, string> = {}): Record<string, string> {
  return {
    'x-mesh-token': TOKEN,
    [HEADER_PROTOCOL]: String(MESH_PROTOCOL),
    [HEADER_INSTANCE]: INSTANCE_ID,
    ...extra
  }
}

async function post(path: string, body: unknown) {
  const r = await fetch(HUB + path, {
    method: 'POST',
    headers: headers({ 'content-type': 'application/json' }),
    body: JSON.stringify(body)
  })
  if (!r.ok) throw await httpError(path, r)
  return r.json()
}

async function httpError(path: string, r: Response): Promise<Error> {
  let detail = ''
  let code = ''
  let activeInstanceId: string | null = null
  let activeStartedAt = 0
  try {
    const body = await r.json() as {
      error?: string
      code?: string
      activeInstanceId?: string | null
      activeStartedAt?: number
    }
    detail = body?.error || ''
    code = body?.code || ''
    activeInstanceId = body?.activeInstanceId ?? null
    activeStartedAt = Number(body?.activeStartedAt || 0) || 0
  } catch { /* not json */ }
  const err = new Error(`${path} -> ${r.status}${code ? ` ${code}` : ''}${detail ? `: ${detail}` : ''}`) as Error & {
    status?: number
    code?: string
    activeInstanceId?: string | null
    activeStartedAt?: number
  }
  err.status = r.status
  err.code = code
  err.activeInstanceId = activeInstanceId
  err.activeStartedAt = activeStartedAt
  return err
}

async function tick() {
  const payload = await collect()
  await post('/api/ingest', payload)
  status.state = 'connected'
  status.lastIngestAt = Date.now()
  status.connectedAt ??= Date.now()
  status.lastError = null

  const r = await fetch(`${HUB}/api/commands?device=${encodeURIComponent(DEVICE)}&status=pending`, {
    headers: headers()
  })
  if (!r.ok) throw await httpError('/api/commands', r)
  const { commands } = await r.json() as { commands: Array<{ id: string; action: string; args: Record<string, unknown> }> }
  for (const cmd of commands) {
    await post(`/api/commands/${cmd.id}/claim`, {})
    let result: { ok: boolean }
    try {
      result = await execute(cmd) as { ok: boolean }
    } catch (err) {
      result = { ok: false, error: (err as Error).message } as { ok: boolean }
    }
    await post('/api/commands/result', { id: cmd.id, ok: result.ok, result })
  }
}

async function chooseControlPort(): Promise<number> {
  for (let p = CONTROL_PORT_BASE; p < CONTROL_PORT_BASE + 10; p++) {
    if (await tcpProbe('127.0.0.1', p, 250) === null) return p
  }
  return CONTROL_PORT_BASE
}

/** Default takeover behavior: park instead of fighting an active instance. */
async function park(): Promise<never> {
  console.error(`[agent] parked: another instance owns ${DEVICE}; restart this process or set MESH_RECLAIM_MS=30000 to stand by`)
  return new Promise<never>(() => {})
}

async function main() {
  console.log(`[agent] ${DEVICE} -> ${HUB} (protocol ${MESH_PROTOCOL}, instance ${INSTANCE_ID})`)

  // Prefer the configured port; otherwise use whichever of 4097 (upstream) or
  // 4096 (badge proxy) is actually listening, so the control plane still works
  // on a host that runs opencode without the proxy.
  const preferred = Number(process.env.OPENCODE_PORT || 4097)
  let port = preferred
  if (await tcpProbe('127.0.0.1', preferred, 400) === null) {
    for (const candidate of [4097, 4096]) {
      if (candidate !== preferred && await tcpProbe('127.0.0.1', candidate, 400) !== null) {
        port = candidate
        break
      }
    }
  }
  console.log(`[agent] opencode target: 127.0.0.1:${port}`)

  controlPort = await chooseControlPort()
  try {
    startControl({
      host: CONTROL_HOST,
      port: controlPort,
      token: TOKEN,
      target: resolveTarget(port),
      instance: { id: INSTANCE_ID, startedAt: INSTANCE_STARTED_AT },
      getStatus: () => ({
        state: status.state,
        lastError: status.lastError,
        lastIngestAt: status.lastIngestAt,
        connectedAt: status.connectedAt
      }),
      log: (m) => console.log(`[control] ${m}`)
    })
    console.log(`[agent] control plane: ${CONTROL_HOST}:${controlPort}`)
  } catch (err) {
    console.error(`[agent] control plane failed to start: ${(err as Error).message}`)
  }

  let retry = RETRY_MIN_MS
  let reclaimDelay = RECLAIM_MIN_MS
  for (;;) {
    let wait = PUSH_MS
    try {
      await tick()
      retry = RETRY_MIN_MS
      if (status.connectedAt && Date.now() - status.connectedAt >= RECLAIM_STABLE_MS) reclaimDelay = RECLAIM_MIN_MS
    } catch (err) {
      const e = err as Error & {
        status?: number
        code?: string
        activeInstanceId?: string | null
        activeStartedAt?: number
      }
      console.error(`[agent] ${e.message}`)
      // 426 is permanent: this agent speaks a protocol the hub refuses.
      if (e.status === 426 || e.code === 'VERSION_MISMATCH') {
        console.error('[agent] protocol mismatch with hub; upgrade the hub or pin MESH_PROTOCOL')
        process.exit(2)
      }
      status.state = 'error'
      status.lastError = e.message
      status.connectedAt = null

      if (e.code === 'AGENT_REPLACED') {
        const activeStartedAt = Number(e.activeStartedAt || 0) || 0
        status.state = 'replaced'
        status.lastError = `replaced by ${e.activeInstanceId ?? 'another agent'}`
        // Default: park, do not fight. Opt-in standby: retry with backoff.
        // The hub's instanceStartedAt rule means an older standby can never
        // preempt a live newer instance; it only wins after that one is gone.
        if (RECLAIM_MIN_MS <= 0) {
          await park()
          return
        }
        wait = Math.max(reclaimDelay, RECLAIM_MIN_MS)
        reclaimDelay = Math.min(Math.max(reclaimDelay * 2, RECLAIM_MIN_MS), RECLAIM_MAX_MS)
        status.state = 'standby'
        console.error(`[agent] standing by (active started ${activeStartedAt || 'unknown'}); retrying in ${wait} ms`)
      } else {
        wait = retry
        retry = Math.min(retry * 2, RETRY_MAX_MS)
      }
    }
    await new Promise(r => setTimeout(r, wait))
  }
}

main()
