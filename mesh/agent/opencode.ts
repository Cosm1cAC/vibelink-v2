// opencode adapter — the only place that knows opencode's HTTP API.
//
// The phone never talks to opencode directly. It calls the mesh control
// methods, the agent translates them here, and everything else stays
// unreachable. That is the same split cindy uses between its mobile client and
// the desktop `REMOTE_INVOKE_ALLOWLIST`.
//
// Targets opencode v2 (`/api/...`, basic auth). A v1 server is detected and
// reported as unsupported instead of silently mis-parsing its payloads.
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { homedir } from 'node:os'

export type OpencodeApi = 'v2' | 'v1' | 'unknown'

export interface OpencodeTarget {
  /** Upstream opencode origin, e.g. http://127.0.0.1:4097 */
  baseUrl: string
  /** Basic-auth password. Empty when the server has auth disabled. */
  password: string
}

export interface SessionSummary {
  id: string
  title: string
  parentID?: string
  created: number
  updated: number
  directory?: string
  agent?: string
  model?: { id: string; providerID: string; variant?: string }
  cost?: number
  tokens?: { input: number; output: number; reasoning?: number }
  outcome?: string
}

export interface ChatTool {
  id: string
  name: string
  state: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'other'
  type: string
  text: string
  reasoning?: string
  tools?: ChatTool[]
  time: number
  completed?: number
  error?: string
}

export interface PermissionRequest {
  id: string
  action: string
  resources: string[]
  message?: string
}

export class OpencodeError extends Error {
  readonly code: 'UNSUPPORTED_VERSION' | 'UPSTREAM' | 'TIMEOUT' | 'BAD_RESPONSE'
  readonly status?: number

  constructor(
    code: 'UNSUPPORTED_VERSION' | 'UPSTREAM' | 'TIMEOUT' | 'BAD_RESPONSE',
    message: string,
    status?: number
  ) {
    super(message)
    this.name = 'OpencodeError'
    this.code = code
    this.status = status
  }
}

const DEFAULT_TIMEOUT_MS = 20_000

export function readPassword(): string {
  const fromEnv = process.env.OPENCODE_SERVER_PASSWORD
  if (fromEnv && fromEnv.trim()) return fromEnv.trim()
  const file = join(homedir(), '.config', 'opencode-server', 'password')
  try {
    return existsSync(file) ? readFileSync(file, 'utf8').trim() : ''
  } catch {
    return ''
  }
}

export function resolveTarget(port = Number(process.env.OPENCODE_PORT || 4097)): OpencodeTarget {
  return {
    baseUrl: (process.env.OPENCODE_BASE_URL || `http://127.0.0.1:${port}`).replace(/\/+$/, ''),
    password: readPassword()
  }
}

function authHeader(target: OpencodeTarget): Record<string, string> {
  if (!target.password) return {}
  const raw = `opencode:${target.password}`
  return { authorization: `Basic ${Buffer.from(raw, 'utf8').toString('base64')}` }
}

async function call<T>(
  target: OpencodeTarget,
  method: 'GET' | 'POST',
  path: string,
  body?: unknown,
  timeoutMs = DEFAULT_TIMEOUT_MS
): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetch(target.baseUrl + path, {
      method,
      headers: {
        ...authHeader(target),
        ...(body === undefined ? {} : { 'content-type': 'application/json' })
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal
    })
    const text = await res.text()
    if (!res.ok) {
      throw new OpencodeError('UPSTREAM', `opencode ${method} ${path} -> ${res.status} ${text.slice(0, 200)}`, res.status)
    }
    if (!text) return {} as T
    try {
      return JSON.parse(text) as T
    } catch {
      throw new OpencodeError('BAD_RESPONSE', `opencode ${path} returned non-JSON`)
    }
  } catch (err) {
    if (err instanceof OpencodeError) throw err
    if ((err as Error).name === 'AbortError') throw new OpencodeError('TIMEOUT', `opencode ${path} timed out`)
    throw new OpencodeError('UPSTREAM', `opencode ${path}: ${(err as Error).message}`)
  } finally {
    clearTimeout(timer)
  }
}

/** Detect the API generation. Never throws: an offline server is `unknown`. */
export async function probe(target: OpencodeTarget): Promise<{ api: OpencodeApi; version: string | null; error?: string }> {
  try {
    const info = await call<{ version?: string }>(target, 'GET', '/api/info', undefined, 5000)
    return { api: 'v2', version: info?.version ?? null }
  } catch (err) {
    const e = err as OpencodeError
    if (e.status === 404) {
      try {
        await call<unknown>(target, 'GET', '/global/health', undefined, 5000)
        return { api: 'v1', version: null }
      } catch {
        /* fall through */
      }
    }
    return { api: 'unknown', version: null, error: e.message }
  }
}

function assertV2(api: OpencodeApi): void {
  if (api === 'v2') return
  throw new OpencodeError(
    'UNSUPPORTED_VERSION',
    api === 'v1'
      ? 'opencode v1 detected: upgrade to v2 (the mesh control plane targets the /api/* surface)'
      : 'opencode is not reachable on the expected port'
  )
}

export async function listSessions(target: OpencodeTarget, limit = 50): Promise<SessionSummary[]> {
  const raw = await call<{ data?: unknown[] }>(target, 'GET', `/api/session?limit=${Math.min(Math.max(limit, 1), 100)}&order=desc`)
  return (raw.data ?? []).map(normalizeSession).filter((s): s is SessionSummary => !!s)
}

export async function getSession(target: OpencodeTarget, id: string): Promise<SessionSummary> {
  const raw = await call<Record<string, unknown>>(target, 'GET', `/api/session/${encodeURIComponent(id)}`)
  const session = normalizeSession(raw)
  if (!session) throw new OpencodeError('BAD_RESPONSE', 'session payload missing id')
  return session
}

function normalizeSession(raw: unknown): SessionSummary | null {
  if (!raw || typeof raw !== 'object') return null
  const s = raw as Record<string, any>
  if (typeof s.id !== 'string') return null
  const time = (s.time ?? {}) as Record<string, number>
  return {
    id: s.id,
    title: typeof s.title === 'string' && s.title ? s.title : s.id,
    parentID: typeof s.parentID === 'string' ? s.parentID : undefined,
    created: Number(time.created ?? 0),
    updated: Number(time.updated ?? time.created ?? 0),
    directory: s.location?.directory,
    agent: typeof s.agent === 'string' ? s.agent : undefined,
    model: s.model,
    cost: typeof s.cost === 'number' ? s.cost : undefined,
    tokens: s.tokens,
    outcome: typeof s.outcome === 'string' ? s.outcome : undefined
  }
}

export async function listMessages(target: OpencodeTarget, id: string, limit = 50): Promise<ChatMessage[]> {
  const raw = await call<{ data?: unknown[] }>(
    target,
    'GET',
    `/api/session/${encodeURIComponent(id)}/message?limit=${Math.min(Math.max(limit, 1), 100)}&order=desc`
  )
  const out: ChatMessage[] = []
  for (const item of raw.data ?? []) {
    const msg = normalizeMessage(item)
    if (msg) out.push(msg)
  }
  return out.reverse()
}

function normalizeMessage(raw: unknown): ChatMessage | null {
  if (!raw || typeof raw !== 'object') return null
  const m = raw as Record<string, any>
  if (typeof m.id !== 'string' || typeof m.type !== 'string') return null
  const time = (m.time ?? {}) as Record<string, number>
  const base: ChatMessage = {
    id: m.id,
    role: m.type === 'user' ? 'user' : m.type === 'assistant' ? 'assistant' : m.type === 'system' ? 'system' : 'other',
    type: m.type,
    text: '',
    time: Number(time.created ?? 0),
    completed: typeof time.completed === 'number' ? time.completed : undefined
  }

  if (m.type === 'user' && typeof m.text === 'string') {
    base.text = m.text
    return base
  }
  if (m.type === 'assistant' && Array.isArray(m.content)) {
    const texts: string[] = []
    const reasoning: string[] = []
    const tools: ChatTool[] = []
    for (const part of m.content) {
      if (!part || typeof part !== 'object') continue
      if (part.type === 'text' && typeof part.text === 'string') texts.push(part.text)
      else if (part.type === 'reasoning' && typeof part.text === 'string') reasoning.push(part.text)
      else if (part.type === 'tool') {
        tools.push({
          id: String(part.id ?? ''),
          name: String(part.name ?? 'tool'),
          state: String(part.state?.type ?? part.state?.status ?? 'unknown')
        })
      }
    }
    base.text = texts.join('\n\n')
    if (reasoning.length) base.reasoning = reasoning.join('\n\n')
    if (tools.length) base.tools = tools
    if (m.finish === 'error') base.error = String(m.rawFinish ?? 'error')
    return base
  }
  if (typeof m.text === 'string') base.text = m.text
  return base
}

export async function sendPrompt(
  target: OpencodeTarget,
  id: string,
  input: { text: string; messageID?: string; delivery?: 'queue' | 'steer' }
): Promise<{ id?: string }> {
  const body: Record<string, unknown> = { text: input.text }
  if (input.messageID) body.id = input.messageID
  if (input.delivery) body.delivery = input.delivery
  return call<{ id?: string }>(target, 'POST', `/api/session/${encodeURIComponent(id)}/prompt`, body, 60_000)
}

export async function interrupt(target: OpencodeTarget, id: string): Promise<unknown> {
  return call(target, 'POST', `/api/session/${encodeURIComponent(id)}/interrupt`, {})
}

export async function listInbox(target: OpencodeTarget, id: string): Promise<unknown[]> {
  const raw = await call<unknown>(target, 'GET', `/api/session/${encodeURIComponent(id)}/inbox`)
  if (Array.isArray(raw)) return raw
  const data = (raw as { data?: unknown })?.data
  return Array.isArray(data) ? data : []
}

export async function listPermissions(target: OpencodeTarget, id: string): Promise<PermissionRequest[]> {
  const raw = await call<unknown>(target, 'GET', `/api/session/${encodeURIComponent(id)}/permission`)
  const list = Array.isArray(raw) ? raw : ((raw as Record<string, unknown>)?.data as unknown[]) ?? []
  return list
    .map((item) => {
      const p = item as Record<string, any>
      if (!p || typeof p.id !== 'string') return null
      return {
        id: p.id,
        action: String(p.action ?? ''),
        resources: Array.isArray(p.resources) ? p.resources.map(String) : [],
        message: typeof p.message === 'string' ? p.message : undefined
      }
    })
    .filter((p): p is PermissionRequest => !!p)
}

export async function replyPermission(
  target: OpencodeTarget,
  id: string,
  requestID: string,
  decision: 'once' | 'always' | 'reject'
): Promise<unknown> {
  return call(
    target,
    'POST',
    `/api/session/${encodeURIComponent(id)}/permission/${encodeURIComponent(requestID)}/reply`,
    { decision }
  )
}

export async function listModels(target: OpencodeTarget): Promise<unknown> {
  return call(target, 'GET', '/api/model')
}

export { assertV2 }
