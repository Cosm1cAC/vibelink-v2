// mesh mobile — take over an agent session from a phone.
//
// The phone is a pure controller: it lists devices, lists sessions on one
// device, streams events, sends prompts and answers approvals. It never talks
// to opencode directly; every call goes through the hub to that device's
// agent, which enforces the allowlist.
//
// Reconnect contract (mirrors cindy's reliable transport in SSE form):
//   * events carry a monotonically increasing `id`
//   * reconnect sends `?since=<last id>` and the agent replays from its ring
//   * a `resync` event means the ring no longer covers the gap: refetch state
//   * a POST carries a stable message id, so a retry cannot send twice
//   * `mesh.agent-replaced` means another agent instance took over; reconnect
//   * every request has a timeout, and screen epochs ignore late responses
const $ = (s) => document.querySelector(s)

const S = {
  token: localStorage.getItem('mesh.token') || '',
  screen: 'devices',
  device: null,
  session: null,
  devices: [],
  sessions: [],
  messages: [],
  permissions: [],
  inbox: [],
  since: 0,
  /** Bumped on every screen/device/session switch: late responses are ignored. */
  epoch: 0,
  /** In-flight HTTP requests, keyed by request id. */
  inFlight: new Map(),
  stream: null,
  streamGen: 0,
  streamState: 'idle',
  streamPhase: 'idle',
  streamError: null,
  streamHint: null,
  streamStartedAt: 0,
  streamReadyAt: 0,
  lastEventAt: 0,
  diag: [],
  path: null,
  rtt: null,
  atBottom: true,
  mode: localStorage.getItem('mesh.delivery') || 'queue'
}

const REQUEST_TIMEOUT_MS = 25_000
const STREAM_CONNECT_TIMEOUT_MS = 10_000
const STREAM_FIRST_EVENT_TIMEOUT_MS = 15_000
const STREAM_IDLE_TIMEOUT_MS = 45_000
const STREAM_RETRY_MAX_MS = 15_000
const DIAG_MAX = 80

const ERROR_HINTS = {
  AGENT_REPLACED: '另一个 agent 实例已接管这台设备；等它退出或重启本 agent',
  DEVICE_OFFLINE: 'agent 心跳已过期；检查目标机器上的 mesh agent',
  UPSTREAM_UNAVAILABLE: 'hub 连不上 agent 控制面；检查 agent 是否在跑、端口是否可达',
  TIMEOUT: '上游超时；重试一次，仍失败就看 agent 日志',
  UNAUTHORIZED: 'hub token 不对；重新输入',
  VERSION_MISMATCH: 'hub/agent 协议版本不一致；升级两端',
  CONGESTED: 'agent 并发已满；稍后重试',
  TOO_MANY_CONNECTIONS: 'SSE 连接数超限；关掉其它页面再试',
  CHANNEL_NOT_ALLOWED: '这个操作不在控制面白名单里',
  NOT_FOUND: '会话/设备不存在或已被删除',
  BAD_REQUEST: '请求参数不对',
  INTERNAL: 'hub/agent 内部错误；看日志',
  ABORTED: '请求已取消',
  STALE: '页面已经切换，旧响应被忽略'
}

function apiError(message, code, status) {
  const err = new Error(message)
  err.code = code
  err.status = status
  err.hint = ERROR_HINTS[code] || ''
  return err
}

function formatErr(e) {
  const msg = e?.message || String(e)
  return e?.hint ? `${msg} · ${e.hint}` : msg
}

function diag(type, detail = {}) {
  const row = { at: Date.now(), type, ...detail }
  S.diag.push(row)
  if (S.diag.length > DIAG_MAX) S.diag.shift()
  if (localStorage.getItem('mesh.debug')) console.debug('[mesh]', type, detail)
}

window.__meshDiag = S.diag

function current(gen) {
  return gen === S.epoch
}

function abortInflight(reason, { keep = false } = {}) {
  for (const [id, entry] of S.inFlight) {
    if (keep && entry.keep) continue
    S.inFlight.delete(id)
    clearTimeout(entry.timer)
    try { entry.ctrl.abort(reason) } catch { /* already settled */ }
    diag('request-aborted', { id, path: entry.path, reason })
  }
}

// ─── helpers ────────────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

function renderText(text) {
  const chunks = String(text ?? '').split(/```/)
  return chunks.map((chunk, i) => {
    if (i % 2 === 1) return `<pre>${esc(chunk.replace(/^[a-zA-Z0-9_+-]*\n/, ''))}</pre>`
    return esc(chunk)
      .replace(/`([^`\n]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>')
  }).join('')
}

function uuid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID()
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = [...b].map(x => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

function age(ms) {
  if (!ms) return '—'
  if (ms < 60_000) return `${Math.round(ms / 1000)}s 前`
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)}m 前`
  if (ms < 86_400_000) return `${Math.round(ms / 3_600_000)}h 前`
  return `${Math.round(ms / 86_400_000)}d 前`
}

function money(v) {
  if (typeof v !== 'number' || !v) return ''
  return v < 0.01 ? `$${v.toFixed(4)}` : `$${v.toFixed(2)}`
}

function toast(msg, ms = 2400) {
  const el = $('#toast')
  el.textContent = msg
  el.classList.add('show')
  clearTimeout(el._h)
  el._h = setTimeout(() => el.classList.remove('show'), ms)
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }

function authHeaders(extra = {}) {
  return S.token ? { 'x-mesh-token': S.token, ...extra } : extra
}

let requestSeq = 0

async function api(path, opts = {}) {
  const { timeoutMs = REQUEST_TIMEOUT_MS, keep = false, signal: external, ...init } = opts
  const id = ++requestSeq
  const ctrl = new AbortController()
  const timer = timeoutMs > 0 ? setTimeout(() => ctrl.abort('timeout'), timeoutMs) : null
  const entry = { id, ctrl, timer, keep, path, startedAt: Date.now() }
  S.inFlight.set(id, entry)
  if (external) {
    if (external.aborted) ctrl.abort(external.reason)
    else external.addEventListener('abort', () => ctrl.abort(external.reason), { once: true })
  }
  try {
    const r = await fetch(path, {
      ...init,
      headers: { ...(init.headers || {}), ...authHeaders() },
      signal: ctrl.signal
    })
    let body = null
    try { body = await r.json() } catch { /* empty */ }
    if (r.status === 401) {
      gate(true)
      throw apiError('token 无效', 'UNAUTHORIZED', 401)
    }
    if (!r.ok) throw apiError(body?.error || `${path} -> ${r.status}`, body?.code || `HTTP_${r.status}`, r.status)
    return body
  } catch (e) {
    if (ctrl.signal.aborted) {
      const timedOut = ctrl.signal.reason === 'timeout'
      const err = apiError(timedOut ? '请求超时' : '请求已取消', timedOut ? 'TIMEOUT' : 'ABORTED')
      diag('request-failed', { id, path, code: err.code, phase: 'request' })
      throw err
    }
    if (e && typeof e === 'object' && !e.hint) {
      e.hint = e.name === 'TypeError' ? '网络不可达或 hub 地址错误' : (ERROR_HINTS[e.code] || '')
    }
    diag('request-failed', { id, path, code: e?.code || 'ERROR', message: e?.message })
    throw e
  } finally {
    if (timer) clearTimeout(timer)
    S.inFlight.delete(id)
  }
}

// ─── gate ───────────────────────────────────────────────────────────────────

function gate(show) {
  $('#gate').classList.toggle('hidden', !show)
  if (show) $('#token').focus()
}

$('#gate-save').onclick = () => {
  const value = $('#token').value.trim()
  if (!value) return toast('先填 token')
  S.token = value
  localStorage.setItem('mesh.token', value)
  gate(false)
  boot()
}

// ─── path chip ──────────────────────────────────────────────────────────────

const PATH_LABEL = {
  local: '本机',
  direct: '直连',
  relay: '中继',
  wan: '公网',
  unknown: '未知'
}

function paintPath() {
  const el = $('#path')
  const p = S.path
  const rtt = S.rtt === null ? '' : ` ${Math.round(S.rtt)}ms`
  if (!p) {
    el.className = 'chip unknown'
    el.textContent = `…${rtt}`
    return
  }
  let text = PATH_LABEL[p.path] || '未知'
  if (p.path === 'direct' && p.reason === 'lan') text = '直连·局域网'
  if (p.path === 'direct' && p.reason === 'tailscale-direct') text = '直连·Tailscale'
  if (p.path === 'relay') text = '中继·DERP'
  el.textContent = text + rtt
  el.className = 'chip ' + (p.path === 'direct' || p.path === 'local' ? 'direct' : p.path === 'relay' ? 'relay' : 'unknown')
  el.title = `path=${p.path} reason=${p.reason} peer=${p.peer}` + (p.tailscale?.relay ? ` relay=${p.tailscale.relay}` : '')
}

async function pollPath() {
  const t0 = performance.now()
  try {
    const info = await api('/api/path')
    S.rtt = performance.now() - t0
    S.path = info
  } catch {
    S.path = null
  }
  paintPath()
}

// ─── screens ────────────────────────────────────────────────────────────────

function setHeader(title, subtitle, showBack) {
  $('#title').textContent = title
  $('#subtitle').textContent = subtitle
  $('#back').classList.toggle('hidden', !showBack)
}

function showDevices() {
  closeStream()
  S.epoch++
  abortInflight('navigate', { keep: true })
  S.screen = 'devices'
  S.device = null
  S.session = null
  $('#composer').classList.add('hidden')
  $('#view').classList.remove('chat')
  setHeader('设备', '选择一台运行 opencode 的机器', false)
  renderDevices()
  loadDevices()
}

function showSessions(device) {
  closeStream()
  S.epoch++
  abortInflight('navigate', { keep: true })
  S.screen = 'sessions'
  S.device = device
  S.session = null
  S.sessions = []
  S.sessionsError = null
  $('#composer').classList.add('hidden')
  $('#view').classList.remove('chat')
  setHeader(device.hostname, device.online ? '在线 · 选择会话' : '离线', true)
  renderSessions()
  loadSessions()
}

function showChat(session) {
  closeStream()
  S.epoch++
  abortInflight('navigate', { keep: true })
  S.screen = 'chat'
  S.session = session
  S.messages = []
  S.permissions = []
  S.inbox = []
  S.since = 0
  S.atBottom = true
  setHeader(session.title || session.id, S.device?.hostname || '', true)
  $('#composer').classList.remove('hidden')
  renderChat()
  loadMessages().then(() => {
    if (S.screen === 'chat' && S.session?.id === session.id) {
      loadPermissions()
      loadInbox()
    }
  })
  openStream()
}

$('#back').onclick = () => {
  if (S.screen === 'chat') return showSessions(S.device)
  if (S.screen === 'sessions') return showDevices()
}

// ─── data ───────────────────────────────────────────────────────────────────

async function loadDevices() {
  const gen = S.epoch
  try {
    const { devices } = await api('/api/devices')
    if (!current(gen) || S.screen !== 'devices') return
    S.devices = devices
    renderDevices()
  } catch (e) {
    if (!current(gen) || S.screen !== 'devices') return
    if (e?.code === 'ABORTED') return
    $('#view').innerHTML = `<div class="banner">${esc(formatErr(e))}</div>`
  }
}

async function loadSessions() {
  if (!S.device) return
  const gen = S.epoch
  const deviceId = S.device.id
  try {
    const { sessions } = await api(`/api/oc/${encodeURIComponent(deviceId)}/sessions?limit=50`)
    if (!current(gen) || S.device?.id !== deviceId || S.screen !== 'sessions') return
    S.sessions = sessions
    S.sessionsError = null
    renderSessions()
  } catch (e) {
    if (!current(gen) || S.device?.id !== deviceId || S.screen !== 'sessions') return
    if (e?.code === 'ABORTED') return
    S.sessionsError = formatErr(e)
    renderSessions()
  }
}

async function loadMessages() {
  if (!S.device || !S.session) return
  const gen = S.epoch
  const deviceId = S.device.id
  const sessionId = S.session.id
  try {
    const { messages } = await api(
      `/api/oc/${encodeURIComponent(deviceId)}/session/${encodeURIComponent(sessionId)}/messages?limit=50`
    )
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId || S.screen !== 'chat') return
    S.messages = messages
    renderChat()
  } catch (e) {
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId || S.screen !== 'chat') return
    if (e?.code === 'ABORTED') return
    toast(formatErr(e))
  }
}

async function loadPermissions() {
  if (!S.device || !S.session) return
  const gen = S.epoch
  const deviceId = S.device.id
  const sessionId = S.session.id
  try {
    const { permissions } = await api(
      `/api/oc/${encodeURIComponent(deviceId)}/session/${encodeURIComponent(sessionId)}/permissions`
    )
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId || S.screen !== 'chat') return
    S.permissions = permissions
    renderChat()
  } catch { /* approvals are optional */ }
}

async function loadInbox() {
  if (!S.device || !S.session) return
  const gen = S.epoch
  const deviceId = S.device.id
  const sessionId = S.session.id
  try {
    const { inbox } = await api(
      `/api/oc/${encodeURIComponent(deviceId)}/session/${encodeURIComponent(sessionId)}/inbox`
    )
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId || S.screen !== 'chat') return
    S.inbox = Array.isArray(inbox) ? inbox : []
    renderChat()
  } catch { /* queue is optional */ }
}

// ─── render ─────────────────────────────────────────────────────────────────

function renderDevices() {
  const view = $('#view')
  if (!S.devices.length) {
    view.innerHTML = '<div class="empty">还没有设备上报</div>'
    return
  }
  view.innerHTML = `<div class="list">${S.devices.map((d) => {
    const oc = d.opencode ? `opencode :${d.opencode.port}` : '无 opencode'
    const proto = d.protocolVersion ? `p${d.protocolVersion}` : 'p1'
    const canTake = !!d.controlPort
    const gate = canTake ? '' : '<span class="badge bad">不可接管</span>'
    const stateBadge = d.agentState === 'replaced' || d.agentState === 'standby'
      ? `<span class="badge bad">${esc(d.agentState)}</span>`
      : ''
    return `<button class="row ${d.online ? 'online' : ''}" ${canTake ? `data-device="${esc(d.id)}"` : 'disabled'}>
      <span class="dot ${d.online ? 'on' : 'off'}"></span>
      <span class="grow">
        <span class="name">${esc(d.hostname)} <span class="badge ${d.online ? 'ok' : 'bad'}">${d.online ? '在线' : '离线'}</span>${stateBadge}${gate}</span>
        <span class="meta">${esc(oc)} · ${esc(proto)} · ${esc(d.ips?.[0] || 'no ip')}</span>
      </span>
      <span class="chev">${canTake ? '›' : ''}</span>
    </button>`
  }).join('')}</div>`
}

function renderSessions() {
  const view = $('#view')
  const banner = S.sessionsError ? `<div class="banner">${esc(S.sessionsError)}</div>` : ''
  if (!S.sessions.length) {
    view.innerHTML = banner + '<div class="empty">这台机器还没有会话</div>'
    return
  }
  view.innerHTML = banner + `<div class="list">${S.sessions.map((s) => {
    const model = s.model ? `${s.model.providerID}/${s.model.id}` : ''
    const meta = [age(Date.now() - (s.updated || 0)), model, money(s.cost)].filter(Boolean).join(' · ')
    return `<button class="row" data-session="${esc(s.id)}">
      <span class="grow">
        <span class="name">${esc(s.title || s.id)}</span>
        <span class="meta">${esc(meta)}</span>
      </span>
      <span class="chev">›</span>
    </button>`
  }).join('')}</div>`
}

function messageHtml(m) {
  const who = m.role === 'user' ? '你' : m.role === 'assistant' ? (m.type === 'assistant' ? 'agent' : m.type) : m.type
  const time = m.time ? new Date(m.time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : ''
  const tools = (m.tools || []).map((t) =>
    `<span class="tool ${esc(t.state)}">${esc(t.name)}</span>`
  ).join('')
  const reasoning = m.reasoning
    ? `<details><summary>思考过程</summary><div class="text">${renderText(m.reasoning)}</div></details>`
    : ''
  const pending = m.role === 'assistant' && !m.completed && !m.error ? '<span class="badge">生成中</span>' : ''
  return `<div class="msg ${esc(m.role)}">
    <div class="who">${esc(who)} · ${esc(time)} ${pending}</div>
    <div class="text">${renderText(m.text || (m.tools?.length ? '' : '（空）'))}</div>
    ${tools ? `<div class="tools">${tools}</div>` : ''}
    ${reasoning}
  </div>`
}

function permissionsHtml() {
  if (!S.permissions.length) return ''
  return S.permissions.map((p) => `<div class="perm" data-perm="${esc(p.id)}">
    <div class="what">${esc(p.action || '需要授权')}</div>
    ${p.resources?.length ? `<div class="res">${esc(p.resources.join(' · '))}</div>` : ''}
    <div class="acts">
      <button class="primary" data-decision="once">允许一次</button>
      <button data-decision="always">总是允许</button>
      <button class="danger" data-decision="reject">拒绝</button>
    </div>
  </div>`).join('')
}

function renderChat() {
  const view = $('#view')
  const streamText =
    S.streamState === 'down' ? (S.streamError || '实时连接断开，正在重连…已收到的消息不会丢') :
    S.streamState === 'connecting' ? (S.streamError || '正在连接事件流…') :
    S.streamState === 'open' ? (S.streamError || '已连上 hub，等待 agent…') :
    ''
  const banner = streamText
    ? `<div class="banner">${esc(streamText)}${S.streamHint ? ` · ${esc(S.streamHint)}` : ''}</div>`
    : ''
  view.classList.add('chat')
  view.innerHTML = `${banner}
    <div class="msgs">${S.messages.map(messageHtml).join('') || '<div class="empty">还没有消息</div>'}</div>
    ${S.inbox.length ? `<div class="empty">排队 ${S.inbox.length} 条待发送</div>` : ''}
    ${permissionsHtml()}`

  if (S.atBottom) window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'instant' })
}

// keep the view pinned to the bottom unless the user scrolled up
window.addEventListener('scroll', () => {
  const el = document.documentElement
  S.atBottom = el.scrollHeight - window.scrollY - window.innerHeight < 120
}, { passive: true })

// ─── actions ────────────────────────────────────────────────────────────────

async function sendMessage() {
  const input = $('#input')
  const text = input.value.trim()
  if (!text || !S.device || !S.session) return
  const gen = S.epoch
  const deviceId = S.device.id
  const sessionId = S.session.id
  const id = uuid()
  const btn = $('#send')
  btn.disabled = true
  try {
    await api(
      `/api/oc/${encodeURIComponent(deviceId)}/session/${encodeURIComponent(sessionId)}/prompt`,
      {
        method: 'POST',
        keep: true,
        headers: { 'content-type': 'application/json', 'x-mesh-request-id': id },
        body: JSON.stringify({ text, messageID: id, delivery: S.mode, requestId: id })
      }
    )
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId) return
    // Only clear what we sent; the user may have typed a follow-up meanwhile.
    if (input.value.trim() === text) {
      input.value = ''
      input.style.height = 'auto'
    }
    S.atBottom = true
    setTimeout(loadMessages, 250)
  } catch (e) {
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId) return
    // Keep the text so a retry is one tap away; the message id is stable.
    if (!input.value.trim()) input.value = text
    toast(`发送失败：${formatErr(e)}`)
  } finally {
    btn.disabled = false
    input.focus()
  }
}

async function stopSession() {
  if (!S.device || !S.session) return
  const gen = S.epoch
  const deviceId = S.device.id
  const sessionId = S.session.id
  try {
    await api(
      `/api/oc/${encodeURIComponent(deviceId)}/session/${encodeURIComponent(sessionId)}/interrupt`,
      { method: 'POST', keep: true, headers: { 'x-mesh-request-id': uuid() } }
    )
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId) return
    toast('已请求中断')
    setTimeout(loadMessages, 400)
  } catch (e) {
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId) return
    toast(formatErr(e))
  }
}

async function replyPermission(requestID, decision) {
  if (!S.device || !S.session) return
  const gen = S.epoch
  const deviceId = S.device.id
  const sessionId = S.session.id
  try {
    await api(
      `/api/oc/${encodeURIComponent(deviceId)}/session/${encodeURIComponent(sessionId)}/permissions/${encodeURIComponent(requestID)}/reply`,
      {
        method: 'POST',
        keep: true,
        headers: { 'content-type': 'application/json', 'x-mesh-request-id': uuid() },
        body: JSON.stringify({ decision })
      }
    )
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId) return
    S.permissions = S.permissions.filter(p => p.id !== requestID)
    renderChat()
  } catch (e) {
    if (!current(gen) || S.device?.id !== deviceId || S.session?.id !== sessionId) return
    toast(formatErr(e))
  }
}

// ─── event stream ───────────────────────────────────────────────────────────

let refreshTimer = null
function scheduleRefresh() {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => {
    loadMessages()
    loadPermissions()
    loadInbox()
    if (S.screen === 'sessions') loadSessions()
  }, 700)
}

function markStreamReady() {
  S.streamState = 'up'
  S.streamPhase = 'ready'
  S.streamError = null
  S.streamHint = null
  if (!S.streamReadyAt) S.streamReadyAt = Date.now()
  if (S.screen === 'chat') renderChat()
}

function streamHint(phase) {
  if (phase === 'connecting') return 'hub 不可达或连接超时；检查网络/hub 地址'
  if (phase === 'open') return 'hub 已连上但 agent 没有响应；检查 agent 是否在线/被接管'
  if (phase === 'ready') return '事件流中断；会自动重连并从 since 续传'
  if (phase === 'upstream-down') return 'agent 的上游 opencode 断了；等它恢复'
  return '事件流异常；看 agent/hub 日志'
}

function handleBlock(block) {
  const lines = block.split('\n')
  let type = 'message'
  let id = null
  const data = []
  for (const line of lines) {
    if (line.startsWith('event:')) type = line.slice(6).trim()
    else if (line.startsWith('id:')) id = line.slice(3).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (id && /^\d+$/.test(id)) S.since = Math.max(S.since, Number(id))
  if (!data.length) return
  let payload = null
  try { payload = JSON.parse(data.join('\n')) } catch { return }

  if (type === 'resync') {
    S.since = 0
    scheduleRefresh()
    return
  }
  if (type === 'server.connected' || type === 'mesh.upstream-up') {
    markStreamReady()
    return
  }
  if (type === 'mesh.upstream-down') {
    S.streamState = 'down'
    S.streamPhase = 'upstream-down'
    S.streamError = payload?.message || 'agent 的上游 opencode 断开'
    S.streamHint = '等它恢复；已收到的消息不会丢'
    renderChat()
    diag('upstream-down', { detail: payload })
    return
  }
  if (type === 'mesh.agent-replaced') {
    S.streamState = 'down'
    S.streamPhase = 'agent-replaced'
    S.streamError = 'agent 已被另一个实例接管'
    S.streamHint = '正在等待新实例接管…'
    renderChat()
    diag('agent-replaced', { detail: payload })
    return
  }
  // Any real event invalidates the current view. Refetching instead of
  // parsing every payload keeps this working across opencode versions.
  if (S.streamState !== 'up') markStreamReady()
  scheduleRefresh()
}

function closeStream() {
  S.streamGen++
  if (S.stream) {
    try { S.stream.abort() } catch { /* already gone */ }
  }
  S.stream = null
  S.streamState = 'idle'
  S.streamPhase = 'idle'
  S.streamError = null
  S.streamHint = null
  S.streamStartedAt = 0
  S.streamReadyAt = 0
  S.lastEventAt = 0
}

async function openStream() {
  closeStream()
  if (!S.device || !S.session) return
  const gen = S.streamGen
  const deviceId = S.device.id
  let backoff = 1000

  while (gen === S.streamGen) {
    const ctrl = new AbortController()
    S.stream = ctrl
    S.streamStartedAt = Date.now()
    S.streamReadyAt = 0
    S.lastEventAt = 0
    S.streamState = 'connecting'
    S.streamPhase = 'connecting'
    S.streamError = null
    S.streamHint = null
    if (S.screen === 'chat') renderChat()
    diag('stream-connect', { device: deviceId, since: S.since })

    let opened = false
    let firstSeen = false
    let connectTimer = null
    let firstTimer = null
    let watchdog = null

    try {
      connectTimer = setTimeout(() => {
        if (gen !== S.streamGen || opened) return
        S.streamError = '连接 hub 超时'
        S.streamHint = '检查手机到 hub 的网络；hub 地址/Tailscale 是否可达'
        ctrl.abort()
      }, STREAM_CONNECT_TIMEOUT_MS)

      const url = `/api/oc/${encodeURIComponent(deviceId)}/events${S.since ? `?since=${S.since}` : ''}`
      const res = await fetch(url, { headers: authHeaders(), signal: ctrl.signal })
      opened = true
      if (connectTimer) { clearTimeout(connectTimer); connectTimer = null }
      if (!res.ok || !res.body) {
        let detail = ''
        let code = `HTTP_${res.status}`
        try {
          const body = await res.json()
          detail = body?.error || ''
          code = body?.code || code
        } catch { /* not json */ }
        throw apiError(detail || `stream -> ${res.status}`, code, res.status)
      }
      S.streamPhase = 'open'
      S.streamState = 'open'
      if (S.screen === 'chat') renderChat()

      firstTimer = setTimeout(() => {
        if (gen !== S.streamGen || firstSeen) return
        S.streamError = 'hub 已连上，但 agent 没有发事件'
        S.streamHint = 'agent 可能离线/正在被接管；看 /api/oc/<device>/status'
        ctrl.abort()
      }, STREAM_FIRST_EVENT_TIMEOUT_MS)

      watchdog = setInterval(() => {
        if (gen !== S.streamGen || ctrl.signal.aborted || !S.lastEventAt) return
        if (Date.now() - S.lastEventAt > STREAM_IDLE_TIMEOUT_MS) {
          S.streamError = '事件流 45s 没有心跳'
          S.streamHint = '手机可能休眠/半死连接；正在重连'
          ctrl.abort()
        }
      }, 5000)

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let idx = buf.indexOf('\n\n')
        while (idx >= 0) {
          const block = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          S.lastEventAt = Date.now()
          const first = !firstSeen
          firstSeen = true
          if (first) {
            if (firstTimer) { clearTimeout(firstTimer); firstTimer = null }
            S.streamReadyAt = Date.now()
            backoff = 1000
            diag('stream-ready', { device: deviceId, ms: S.streamReadyAt - S.streamStartedAt })
            markStreamReady()
          }
          handleBlock(block)
          idx = buf.indexOf('\n\n')
        }
        if (buf.length > 2 * 1024 * 1024) buf = ''
      }
      if (!ctrl.signal.aborted) throw new Error('event stream closed')
    } catch (e) {
      if (gen !== S.streamGen) return
      const phase = S.streamPhase
      const code = e?.code || (ctrl.signal.aborted ? 'ABORTED' : 'STREAM')
      const message = S.streamError || e?.message || '事件流中断'
      const hint = S.streamHint || e?.hint || streamHint(phase)
      S.streamError = message
      S.streamHint = hint
      S.streamState = 'down'
      S.streamPhase = 'retrying'
      if (S.screen === 'chat') renderChat()
      diag('stream-disconnect', {
        device: deviceId,
        phase,
        code,
        message,
        hint,
        lastedMs: S.streamReadyAt ? Date.now() - S.streamReadyAt : null
      })
      // The transport is gone; settle every request riding on it so the UI
      // does not keep a spinner or accept a late response for another screen.
      abortInflight('stream-down')
    } finally {
      if (connectTimer) clearTimeout(connectTimer)
      if (firstTimer) clearTimeout(firstTimer)
      if (watchdog) clearInterval(watchdog)
    }

    if (gen !== S.streamGen) return
    await sleep(backoff)
    backoff = Math.min(backoff * 2, STREAM_RETRY_MAX_MS)
  }
}

// ─── events ─────────────────────────────────────────────────────────────────

document.addEventListener('click', (ev) => {
  const btn = ev.target.closest('button')
  if (!btn) return

  if (btn.dataset.device) {
    const device = S.devices.find(d => d.id === btn.dataset.device)
    if (device) showSessions(device)
    return
  }
  if (btn.dataset.session) {
    const session = S.sessions.find(s => s.id === btn.dataset.session)
    if (session) showChat(session)
    return
  }
  if (btn.dataset.decision) {
    const perm = btn.closest('[data-perm]')
    if (perm) replyPermission(perm.dataset.perm, btn.dataset.decision)
    return
  }
  if (btn.id === 'send') return sendMessage()
  if (btn.id === 'stop') return stopSession()
})

function paintMode() {
  const el = $('#mode')
  el.textContent = S.mode === 'queue' ? '排队' : '插话'
  el.className = 'mode' + (S.mode === 'steer' ? ' steer' : '')
}
$('#mode').addEventListener('click', () => {
  S.mode = S.mode === 'queue' ? 'steer' : 'queue'
  localStorage.setItem('mesh.delivery', S.mode)
  paintMode()
})

const input = $('#input')
input.addEventListener('input', () => {
  input.style.height = 'auto'
  input.style.height = Math.min(input.scrollHeight, 132) + 'px'
})
input.addEventListener('keydown', (ev) => {
  if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing) {
    ev.preventDefault()
    sendMessage()
  }
})

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') return
  pollPath()
  if (S.screen !== 'chat') return
  loadMessages()
  loadPermissions()
  loadInbox()
  const stalledConnect = S.streamPhase === 'connecting'
    && S.streamStartedAt > 0
    && Date.now() - S.streamStartedAt > STREAM_CONNECT_TIMEOUT_MS
  const stale = S.lastEventAt > 0 && Date.now() - S.lastEventAt > STREAM_IDLE_TIMEOUT_MS
  if (!S.stream || S.stream.signal.aborted || stalledConnect || stale || S.streamState === 'down') openStream()
})

window.addEventListener('online', () => {
  if (S.screen === 'chat') openStream()
})

window.addEventListener('offline', () => {
  if (S.screen !== 'chat') return
  S.streamState = 'down'
  S.streamPhase = 'offline'
  S.streamError = '网络已断开'
  S.streamHint = '等待网络恢复后自动重连'
  renderChat()
  abortInflight('offline')
})

// ─── boot ───────────────────────────────────────────────────────────────────

// One-tap onboarding: /m.html#t=<token>. The fragment is never sent to the
// server (unlike ?t=), and it is cleared from the address bar right after it
// is stored, so it does not linger in history.
{
  const hash = new URLSearchParams(location.hash.replace(/^#/, ''))
  const fromHash = hash.get('t')
  if (fromHash) {
    S.token = fromHash.trim()
    localStorage.setItem('mesh.token', S.token)
    history.replaceState(null, '', location.pathname + location.search)
  }
}

async function boot() {
  paintMode()
  await pollPath()
  showDevices()
  if (S.booted) return
  S.booted = true
  setInterval(pollPath, 10_000)
  setInterval(() => { if (S.screen === 'devices') loadDevices() }, 5_000)
  setInterval(() => { if (S.screen === 'sessions') loadSessions() }, 8_000)
}

if (!S.token) {
  gate(true)
} else {
  gate(false)
  boot()
}
