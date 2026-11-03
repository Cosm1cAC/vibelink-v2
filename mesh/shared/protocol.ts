// mesh wire protocol — shared constants and error codes.
//
// Both the hub and the agent import this file, so the two sides cannot drift
// on limits or error codes. Zero dependencies; runs on Node >= 22.6.
//
// Wire mapping (HTTP) of the close codes cindy uses on its WebSocket relay:
//   4400 VERSION_MISMATCH -> HTTP 426  code=VERSION_MISMATCH
//   4410 DEVICE_REMOVED   -> HTTP 403  code=DEVICE_REMOVED
//   4429 TOO_MANY_CONNS   -> HTTP 429  code=TOO_MANY_CONNECTIONS
//   1013 CONGESTED        -> HTTP 503  code=CONGESTED
//   4000 AGENT_REPLACED   -> HTTP 409  code=AGENT_REPLACED
// plus PAYLOAD_TOO_LARGE -> HTTP 413, DEVICE_OFFLINE -> HTTP 502.
//
// See PROTOCOL.md for the full contract.

/** Bumped when a change is not backward compatible. */
export const MESH_PROTOCOL = 2
/** Oldest agent protocol the hub still accepts. */
export const MESH_PROTOCOL_MIN = 1

export const HEADER_PROTOCOL = 'x-mesh-protocol'
export const HEADER_TOKEN = 'x-mesh-token'
export const HEADER_REQUEST_ID = 'x-mesh-request-id'
/** Agent process identity. Optional for older agents (legacy, single writer). */
export const HEADER_INSTANCE = 'x-mesh-instance'

export const LIMITS = {
  /** /api/ingest — one device snapshot. */
  ingestBytes: 64 * 1024,
  /** /api/commands — one queued action. */
  commandBytes: 16 * 1024,
  /** /api/commands/result — action result. */
  commandResultBytes: 64 * 1024,
  /** /api/config/<name> — uploaded config body. */
  configBytes: 1024 * 1024,
  /** agent control plane request body. */
  controlBytes: 256 * 1024,
  /** largest single SSE frame forwarded to a phone. */
  eventBytes: 1024 * 1024,
  /** pending commands allowed per device before the hub pushes back. */
  pendingCommandsPerDevice: 32,
  /** registered devices. */
  maxDevices: 64,
  /** concurrently open SSE streams per agent. */
  maxEventStreams: 8,
  /** events kept for Last-Event-ID replay. */
  eventRing: 500,
  /** events kept for replay, by age. */
  eventRingMs: 5 * 60_000
} as const

export type MeshErrorCode =
  | 'BAD_REQUEST'
  | 'UNAUTHORIZED'
  | 'NOT_FOUND'
  | 'VERSION_MISMATCH'
  | 'DEVICE_REMOVED'
  | 'AGENT_REPLACED'
  | 'DEVICE_OFFLINE'
  | 'TOO_MANY_CONNECTIONS'
  | 'TOO_MANY_COMMANDS'
  | 'PAYLOAD_TOO_LARGE'
  | 'CONGESTED'
  | 'UPSTREAM_UNAVAILABLE'
  | 'TIMEOUT'
  | 'CHANNEL_NOT_ALLOWED'
  | 'INTERNAL'

/** HTTP status for each stable error code. */
export const ERROR_STATUS: Record<MeshErrorCode, number> = {
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  NOT_FOUND: 404,
  VERSION_MISMATCH: 426,
  DEVICE_REMOVED: 403,
  AGENT_REPLACED: 409,
  DEVICE_OFFLINE: 502,
  TOO_MANY_CONNECTIONS: 429,
  TOO_MANY_COMMANDS: 429,
  PAYLOAD_TOO_LARGE: 413,
  CONGESTED: 503,
  UPSTREAM_UNAVAILABLE: 502,
  TIMEOUT: 504,
  CHANNEL_NOT_ALLOWED: 403,
  INTERNAL: 500
}

export interface MeshErrorBody {
  error: string
  code: MeshErrorCode
  /** Present on VERSION_MISMATCH. */
  hubProtocol?: number
  /** Present on VERSION_MISMATCH. */
  peerProtocol?: number
  /** Present on AGENT_REPLACED: the instance that currently owns the device. */
  activeInstanceId?: string | null
  /** Present on AGENT_REPLACED: process start time of the active instance. */
  activeStartedAt?: number
}

/**
 * Control-plane methods the phone may invoke. Deny-by-default: anything not
 * listed here is rejected by the agent before it reaches opencode.
 *
 * Mirrors cindy's `REMOTE_INVOKE_ALLOWLIST`: only session lifecycle, send /
 * steer / stop, approvals and read-only listing. Never shell, fs write,
 * credentials, config or updater.
 */
export const CONTROL_METHODS = {
  'sessions.list': { verb: 'GET', path: '/sessions' },
  'session.get': { verb: 'GET', path: '/session/:id' },
  'session.messages': { verb: 'GET', path: '/session/:id/messages' },
  'session.prompt': { verb: 'POST', path: '/session/:id/prompt' },
  'session.interrupt': { verb: 'POST', path: '/session/:id/interrupt' },
  'session.inbox': { verb: 'GET', path: '/session/:id/inbox' },
  'session.permissions': { verb: 'GET', path: '/session/:id/permissions' },
  'session.permission.reply': { verb: 'POST', path: '/session/:id/permissions/:requestID/reply' },
  'models.list': { verb: 'GET', path: '/models' },
  health: { verb: 'GET', path: '/health' },
  status: { verb: 'GET', path: '/status' }
} as const

export type ControlMethod = keyof typeof CONTROL_METHODS

export function isControlMethod(value: string): value is ControlMethod {
  return Object.prototype.hasOwnProperty.call(CONTROL_METHODS, value)
}
