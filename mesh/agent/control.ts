// agent control plane — the allowlisted surface a phone is allowed to drive.
//
// Every method here maps to one opencode call. Anything not in the table is
// rejected before it reaches opencode (deny by default), which is the same
// boundary cindy enforces with REMOTE_INVOKE_ALLOWLIST.
//
// Two properties matter for a phone on a flaky link:
//   * idempotency — POSTs carry x-mesh-request-id; a retry replays the cached
//     result instead of sending the prompt twice.
//   * resumability — /events keeps a bounded ring buffer and honours
//     Last-Event-ID, so a reconnect resumes instead of losing messages.
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { timingSafeEqual } from 'node:crypto'
import {
  HEADER_PROTOCOL,
  HEADER_REQUEST_ID,
  HEADER_TOKEN,
  LIMITS,
  MESH_PROTOCOL,
  MESH_PROTOCOL_MIN,
  type MeshErrorCode
} from '../shared/protocol.ts'
import * as oc from './opencode.ts'

export interface ControlOptions {
  host: string
  port: number
  token: string
  target: oc.OpencodeTarget
  /** Process identity of this agent; echoed by /status and /health. */
  instance?: { id: string; startedAt: number }
  /** Live hub-link state maintained by the agent's main loop. */
  getStatus?: () => {
    state: string
    lastError: string | null
    lastIngestAt: number
    connectedAt: number | null
  }
  log: (message: string) => void
}

type Handler = (ctx: {
  params: Record<string, string>
  query: URLSearchParams
  body: any
}) => Promise<unknown>

interface Route {
  method: 'GET' | 'POST'
  pattern: RegExp
  keys: string[]
  handler: Handler
}

const MAX_INFLIGHT = 8
// Interactive writes (send / steer / stop / approve) get a couple of extra
// slots so a wall of history reads cannot make a human's send wait. Same
// intent as cindy's InvokeScheduler INTERACTIVE_EXTRA_ACTIVE.
const INTERACTIVE_EXTRA = 2
const DEDUPE_TTL_MS = 10 * 60_000
const DEDUPE_MAX = 256

function route(method: 'GET' | 'POST', path: string, handler: Handler): Route {
  const keys: string[] = []
  const pattern = new RegExp(
    '^' +
      path.replace(/:[A-Za-z]+/g, (m) => {
        keys.push(m.slice(1))
        return '([^/]+)'
      }) +
      '$'
  )
  return { method, pattern, keys, handler }
}

function json(res: ServerResponse, status: number, body: unknown, extra: Record<string, string> = {}) {
  const buf = Buffer.from(JSON.stringify(body), 'utf8')
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': buf.length,
    'cache-control': 'no-store',
    'x-mesh-protocol': String(MESH_PROTOCOL),
    ...extra
  })
  res.end(buf)
}

function fail(res: ServerResponse, code: MeshErrorCode, message: string, status?: number) {
  json(res, status ?? STATUS[code] ?? 500, { error: message, code })
}

const STATUS: Record<string, number> = {
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  NOT_FOUND: 404,
  VERSION_MISMATCH: 426,
  DEVICE_REMOVED: 403,
  AGENT_REPLACED: 409,
  PAYLOAD_TOO_LARGE: 413,
  TOO_MANY_CONNECTIONS: 429,
  TOO_MANY_COMMANDS: 429,
  CONGESTED: 503,
  UPSTREAM_UNAVAILABLE: 502,
  TIMEOUT: 504,
  CHANNEL_NOT_ALLOWED: 403,
  INTERNAL: 500
}

function readBody(req: IncomingMessage, limit: number): Promise<string> {
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

function sameToken(a: string, b: string): boolean {
  const x = Buffer.from(a, 'utf8')
  const y = Buffer.from(b, 'utf8')
  return x.length === y.length && timingSafeEqual(x, y)
}

function mapError(err: unknown): { code: MeshErrorCode; message: string; status: number } {
  if (err instanceof oc.OpencodeError) {
    if (err.code === 'TIMEOUT') return { code: 'TIMEOUT', message: err.message, status: 504 }
    if (err.status === 404) return { code: 'NOT_FOUND', message: err.message, status: 404 }
    return { code: 'UPSTREAM_UNAVAILABLE', message: err.message, status: 502 }
  }
  const e = err as { code?: string; message?: string }
  if (e?.code === 'PAYLOAD_TOO_LARGE') return { code: 'PAYLOAD_TOO_LARGE', message: 'payload too large', status: 413 }
  if (e?.code === 'BAD_REQUEST') return { code: 'BAD_REQUEST', message: e.message ?? 'bad request', status: 400 }
  return { code: 'INTERNAL', message: e?.message ?? 'internal error', status: 500 }
}

// ─── event fan-out ──────────────────────────────────────────────────────────

interface BufferedEvent {
  seq: number
  id: string | null
  type: string
  data: unknown
  at: number
}

class EventHub {
  private seq = 0
  private ring: BufferedEvent[] = []
  private clients = new Set<ServerResponse>()
  private upstream: AbortController | null = null
  private retry = 1000
  private heartbeat: ReturnType<typeof setInterval> | null = null
  private readonly target: oc.OpencodeTarget
  private readonly log: (m: string) => void

  constructor(target: oc.OpencodeTarget, log: (m: string) => void) {
    this.target = target
    this.log = log
  }

  get size(): number {
    return this.clients.size
  }

  subscribe(req: IncomingMessage, res: ServerResponse, sinceParam: string | null): boolean {
    if (this.clients.size >= LIMITS.maxEventStreams) return false

    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
      'x-mesh-protocol': String(MESH_PROTOCOL)
    })
    res.write(': mesh event stream\n\n')
    this.write(res, 'server.connected', { at: Date.now(), protocol: MESH_PROTOCOL })
    // Join before replay so an event pushed mid-replay is not lost; the client
    // tolerates the resulting duplicate because it advances `since` by max().
    this.clients.add(res)

    const since = Number(sinceParam ?? '')
    if (Number.isFinite(since) && since > 0) {
      const oldest = this.ring[0]?.seq ?? this.seq + 1
      // `since > this.seq` means the agent restarted and its counter went
      // backwards; treat it like an evicted gap instead of silently ignoring
      // every new event until the old number is passed again.
      if (since < oldest - 1 || since > this.seq) {
        this.write(res, 'resync', { oldest, latest: this.seq, reason: since > this.seq ? 'agent-restarted' : 'buffer-evicted' })
      } else {
        for (const ev of this.ring) if (ev.seq > since) this.write(res, ev.type, ev.data, ev.seq, ev.id)
      }
    }

    this.ensureUpstream()

    req.on('close', () => {
      this.clients.delete(res)
      if (this.clients.size === 0) {
        this.upstream?.abort()
        this.upstream = null
      }
    })
    return true
  }

  private write(res: ServerResponse, type: string, data: unknown, seq?: number, id?: string | null) {
    if (seq !== undefined) res.write(`id: ${seq}\n`)
    if (id) res.write(`x-opencode-event: ${id}\n`)
    res.write(`event: ${type}\n`)
    const payload = JSON.stringify(data ?? null)
    for (const line of payload.split('\n')) res.write(`data: ${line}\n`)
    res.write('\n')
  }

  private broadcast(ev: BufferedEvent) {
    for (const res of this.clients) {
      // Slow consumer: drop the stream instead of growing an unbounded buffer.
      // The client reconnects with Last-Event-ID and replays from the ring.
      if (res.writableLength > 1024 * 1024) {
        this.clients.delete(res)
        res.destroy()
        continue
      }
      try {
        this.write(res, ev.type, ev.data, ev.seq, ev.id)
      } catch {
        this.clients.delete(res)
      }
    }
  }

  private push(id: string | null, type: string, data: unknown) {
    const ev: BufferedEvent = { seq: ++this.seq, id, type, data, at: Date.now() }
    this.ring.push(ev)
    const cutoff = Date.now() - LIMITS.eventRingMs
    while (this.ring.length > LIMITS.eventRing || (this.ring[0] && this.ring[0].at < cutoff)) this.ring.shift()
    this.broadcast(ev)
  }

  private ensureUpstream() {
    if (this.upstream) return
    this.upstream = new AbortController()
    void this.pump(this.upstream.signal)
  }

  private async pump(signal: AbortSignal) {
    const url = `${this.target.baseUrl}/api/event`
    const headers: Record<string, string> = { accept: 'text/event-stream' }
    if (this.target.password) {
      headers.authorization = `Basic ${Buffer.from(`opencode:${this.target.password}`, 'utf8').toString('base64')}`
    }
    try {
      const res = await fetch(url, { headers, signal })
      if (!res.ok || !res.body) throw new Error(`event stream -> ${res.status}`)
      this.retry = 1000
      this.log('event stream connected')
      this.push(null, 'mesh.upstream-up', { at: Date.now() })
      const decoder = new TextDecoder()
      let buf = ''
      for await (const chunk of res.body as any) {
        buf += decoder.decode(chunk, { stream: true })
        let idx = buf.indexOf('\n\n')
        while (idx >= 0) {
          const block = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          this.handleBlock(block)
          idx = buf.indexOf('\n\n')
        }
        if (buf.length > LIMITS.eventBytes * 2) buf = ''
      }
      throw new Error('event stream closed')
    } catch (err) {
      if (signal.aborted) return
      this.log(`event stream error: ${(err as Error).message}`)
      this.push(null, 'mesh.upstream-down', { message: (err as Error).message })
      this.upstream = null
      setTimeout(() => this.ensureUpstream(), this.retry)
      this.retry = Math.min(this.retry * 2, 30_000)
    }
  }

  private handleBlock(block: string) {
    const data: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (!data.length) return
    let parsed: any
    try {
      parsed = JSON.parse(data.join('\n'))
    } catch {
      return
    }
    const type = typeof parsed?.type === 'string' ? parsed.type : 'message'
    const id = typeof parsed?.id === 'string' ? parsed.id : null
    let payload = parsed?.data ?? parsed
    if (JSON.stringify(payload ?? null).length > LIMITS.eventBytes) {
      payload = { truncated: true, originalType: type }
    }
    this.push(id, type, payload)
  }

  startHeartbeat() {
    this.heartbeat = setInterval(() => {
      for (const res of this.clients) {
        try {
          res.write(': heartbeat\n\n')
        } catch {
          this.clients.delete(res)
        }
      }
    }, 15_000)
    this.heartbeat.unref?.()
  }

  stop() {
    if (this.heartbeat) clearInterval(this.heartbeat)
    this.upstream?.abort()
    for (const res of this.clients) res.destroy()
    this.clients.clear()
  }
}

// ─── server ─────────────────────────────────────────────────────────────────

export function startControl(opts: ControlOptions) {
  const { target, token, log } = opts
  const hub = new EventHub(target, log)
  hub.startHeartbeat()

  let inflight = 0
  const dedupe = new Map<string, { at: number; status: number; body: unknown }>()
  let probeCache: { at: number; value: Awaited<ReturnType<typeof oc.probe>> } | null = null

  async function currentProbe() {
    if (probeCache && Date.now() - probeCache.at < 10_000) return probeCache.value
    const value = await oc.probe(target)
    probeCache = { at: Date.now(), value }
    return value
  }

  function runtimeStatus() {
    return {
      instance: { ...(opts.instance ?? {}), ...(opts.getStatus?.() ?? {}) },
      events: hub.size,
      inflight
    }
  }

  const routes: Route[] = [
    route('GET', '/status', async () => ({
      ok: true,
      protocol: MESH_PROTOCOL,
      ...runtimeStatus()
    })),
    route('GET', '/health', async () => {
      const p = await currentProbe()
      return { ok: p.api === 'v2', protocol: MESH_PROTOCOL, ...runtimeStatus(), opencode: p }
    }),
    route('GET', '/sessions', async ({ query }) => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      return { sessions: await oc.listSessions(target, Number(query.get('limit') || 50)) }
    }),
    route('GET', '/session/:id', async ({ params }) => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      return { session: await oc.getSession(target, params.id) }
    }),
    route('GET', '/session/:id/messages', async ({ params, query }) => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      return { messages: await oc.listMessages(target, params.id, Number(query.get('limit') || 50)) }
    }),
    route('GET', '/session/:id/inbox', async ({ params }) => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      return { inbox: await oc.listInbox(target, params.id) }
    }),
    route('GET', '/session/:id/permissions', async ({ params }) => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      return { permissions: await oc.listPermissions(target, params.id) }
    }),
    route('GET', '/models', async () => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      return { models: await oc.listModels(target) }
    }),
    route('POST', '/session/:id/prompt', async ({ params, body }) => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      const text = typeof body?.text === 'string' ? body.text : ''
      if (!text.trim()) throw Object.assign(new Error('text required'), { code: 'BAD_REQUEST' })
      const delivery = body?.delivery === 'steer' ? 'steer' : 'queue'
      const result = await oc.sendPrompt(target, params.id, {
        text,
        messageID: typeof body?.messageID === 'string' ? body.messageID : undefined,
        delivery
      })
      return { ok: true, result }
    }),
    route('POST', '/session/:id/interrupt', async ({ params }) => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      return { ok: true, result: await oc.interrupt(target, params.id) }
    }),
    route('POST', '/session/:id/permissions/:requestID/reply', async ({ params, body }) => {
      await currentProbe().then((p) => oc.assertV2(p.api))
      const decision = body?.decision
      if (decision !== 'once' && decision !== 'always' && decision !== 'reject') {
        throw Object.assign(new Error('decision must be once|always|reject'), { code: 'BAD_REQUEST' })
      }
      return { ok: true, result: await oc.replyPermission(target, params.id, params.requestID, decision) }
    })
  ]

  const server = createServer(async (req, res) => {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`)
    const path = url.pathname

    // protocol negotiation
    const peerProtocol = Number(req.headers[HEADER_PROTOCOL] ?? '1')
    if (!Number.isFinite(peerProtocol) || peerProtocol < MESH_PROTOCOL_MIN) {
      return json(res, 426, {
        error: 'protocol too old',
        code: 'VERSION_MISMATCH',
        hubProtocol: MESH_PROTOCOL,
        peerProtocol
      })
    }
    if (peerProtocol > MESH_PROTOCOL) {
      return json(res, 426, {
        error: 'protocol newer than this agent',
        code: 'VERSION_MISMATCH',
        hubProtocol: MESH_PROTOCOL,
        peerProtocol
      })
    }

    const provided = String(req.headers[HEADER_TOKEN] ?? '')
    if (!token || !sameToken(provided, token)) return fail(res, 'UNAUTHORIZED', 'bad token')

    if (req.method === 'GET' && path === '/events') {
      const since = url.searchParams.get('since') ?? (req.headers['last-event-id'] as string | undefined) ?? null
      if (!hub.subscribe(req, res, since)) return fail(res, 'TOO_MANY_CONNECTIONS', 'too many event streams')
      return
    }

    const match = routes.find((r) => r.method === req.method && r.pattern.test(path))
    if (!match) return fail(res, 'CHANNEL_NOT_ALLOWED', `method not allowed: ${req.method} ${path}`)

    if (inflight >= MAX_INFLIGHT) {
      const interactive = /\/(prompt|interrupt)$/.test(path) || /\/permissions\/[^/]+\/reply$/.test(path)
      if (!interactive || inflight >= MAX_INFLIGHT + INTERACTIVE_EXTRA) {
        return fail(res, 'CONGESTED', 'too many in-flight requests')
      }
    }
    inflight++

    let body: any = null
    try {
      if (req.method === 'POST') {
        const raw = await readBody(req, LIMITS.controlBytes)
        try {
          body = raw ? JSON.parse(raw) : {}
        } catch {
          throw Object.assign(new Error('invalid JSON body'), { code: 'BAD_REQUEST' })
        }
      }
    } catch (err) {
      inflight--
      const mapped = mapError(err)
      return fail(res, mapped.code, mapped.message, mapped.status)
    }

    const requestId =
      (req.headers[HEADER_REQUEST_ID] as string | undefined) ||
      (typeof body?.requestId === 'string' ? body.requestId : undefined)

    if (requestId) {
      const hit = dedupe.get(requestId)
      if (hit && Date.now() - hit.at < DEDUPE_TTL_MS) {
        inflight--
        return json(res, hit.status, hit.body, { 'x-mesh-replayed': '1' })
      }
    }

    const params: Record<string, string> = {}
    const m = match.pattern.exec(path)
    if (m) match.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])))

    try {
      const result = await match.handler({ params, query: url.searchParams, body })
      if (requestId) {
        dedupe.set(requestId, { at: Date.now(), status: 200, body: result })
        while (dedupe.size > DEDUPE_MAX) {
          const oldest = dedupe.keys().next().value as string
          dedupe.delete(oldest)
        }
      }
      json(res, 200, result)
    } catch (err) {
      const mapped = mapError(err)
      if (mapped.code === 'UPSTREAM_UNAVAILABLE' || mapped.code === 'TIMEOUT') {
        log(`control error ${path}: ${mapped.message}`)
      }
      json(res, mapped.status, { error: mapped.message, code: mapped.code })
    } finally {
      inflight--
    }
  })

  server.on('error', (err) => log(`control plane error: ${(err as Error).message}`))
  server.listen(opts.port, opts.host, () => {
    log(`control plane on http://${opts.host}:${opts.port} (opencode ${target.baseUrl})`)
  })

  return {
    server,
    hub,
    close: () => {
      hub.stop()
      server.close()
    }
  }
}
