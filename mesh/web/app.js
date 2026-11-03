// mesh dashboard - vanilla JS, no build step.
const $ = (s) => document.querySelector(s)
const state = { token: localStorage.getItem('mesh.token') || '', devices: [], configs: [], timer: null }

function toast(msg, ms = 2200) {
  const t = $('#toast')
  t.textContent = msg
  t.classList.add('show')
  clearTimeout(t._h)
  t._h = setTimeout(() => t.classList.remove('show'), ms)
}

function authHeaders() {
  return state.token ? { 'x-mesh-token': state.token } : {}
}

async function api(path, opts = {}) {
  const r = await fetch(path, { ...opts, headers: { ...(opts.headers || {}), ...authHeaders() } })
  if (r.status === 401) { toast('token 无效，请重新输入'); throw new Error('unauthorized') }
  if (!r.ok) throw new Error(`${path} -> ${r.status}`)
  return r.json()
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

function fmtAge(ms) {
  if (ms < 60000) return `${Math.round(ms / 1000)}s 前`
  if (ms < 3600000) return `${Math.round(ms / 60000)}m 前`
  return `${Math.round(ms / 3600000)}h 前`
}

function ms(v) { return v === null || v === undefined ? '—' : `${v}ms` }

function renderTopology(devices) {
  const row = $('#devices-row')
  row.innerHTML = devices.map((d) => `
    <div class="dev-node ${d.online ? 'online' : ''}">
      <div class="name"><span class="dot ${d.online ? 'on' : 'off'}"></span>${esc(d.hostname)}</div>
      <div class="sub">${esc(d.ips[0] || 'no ip')}</div>
      <div class="sub">${d.proxy?.server ? esc(d.proxy.server) : '直连'}</div>
      <div class="sub">${d.latency?.connectMs ? `出口 ${d.latency.connectMs}ms` : (d.latency?.tcpMs ? `TCP ${d.latency.tcpMs}ms` : '—')}</div>
    </div>`).join('') || '<div class="hint">还没有设备上报</div>'
  $('#topo-hint').textContent = `${devices.filter(d => d.online).length}/${devices.length} 在线`
}

function renderCards(devices) {
  $('#cards').innerHTML = devices.map((d) => {
    const ports = Object.entries(d.ports || {}).filter(([, v]) => v).map(([k]) => k).join(' ') || '—'
    return `
    <article class="card ${d.online ? 'online' : ''}" data-id="${esc(d.id)}">
      <header>
        <span class="dot ${d.online ? 'on' : 'off'}"></span>
        <h3>${esc(d.hostname)}</h3>
        <span class="badge ${d.online ? 'ok' : 'bad'}">${d.online ? '在线' : '离线'}</span>
        <span class="badge">${esc(d.id)}</span>
        <span class="badge">${d.agentVersion || '?'}</span>
      </header>
      <dl class="kv">
        <dt>系统</dt><dd>${esc(d.platform)} ${esc(d.arch)}</dd>
        <dt>IP</dt><dd>${esc(d.ips.join(', ') || '—')}</dd>
        <dt>网关</dt><dd>${esc(d.gateway || '—')}</dd>
        <dt>系统代理</dt><dd>${d.proxy?.enabled ? esc(d.proxy.server) : '未启用'}</dd>
        <dt>出口探测</dt><dd>${d.latency ? `${esc(d.latency.target)} · TCP ${ms(d.latency.tcpMs)} · CONNECT ${ms(d.latency.connectMs)}` : '—'}</dd>
        <dt>监听</dt><dd>${esc(ports)}</dd>
        <dt>内存</dt><dd>${d.mem?.free ?? '?'}MB 空闲 / ${d.mem?.total ?? '?'}MB</dd>
        <dt>心跳</dt><dd>${fmtAge(d.ageMs)}</dd>
      </dl>
      <div class="actions">
        <input placeholder="host:port" value="${esc(d.proxy?.server || '')}" data-role="proxy">
        <button data-act="set-proxy">设置代理</button>
        <button data-act="clear-proxy" class="ghost">清除</button>
        <select data-role="svc" class="badge">
          ${['WProxy', 'WProxyHysteria', 'PcProxyFailover', 'PcLanGateway', 'PhoneConfigHttp'].map(s => `<option>${s}</option>`).join('')}
        </select>
        <button data-act="restart-service" class="ghost">重启服务</button>
        <button data-act="probe" class="ghost">探测</button>
        ${d.opencode ? `<a class="badge ok" href="http://${esc(d.ips[0])}:${d.opencode.port}/" target="_blank" rel="noreferrer">opencode :${d.opencode.port}</a>` : ''}
      </div>
    </article>`
  }).join('') || '<div class="hint">还没有设备上报</div>'
}

function renderConfigs(list) {
  const base = location.origin
  $('#configs').innerHTML = list.map((c) => {
    const url = `${base}/phone/${encodeURIComponent(c.name)}?t=${encodeURIComponent(state.token)}`
    return `<div class="cfg">
      <div>
        <div><b>${esc(c.name)}</b></div>
        <div class="hint">${c.versions.length} 个版本 · 最新 ${esc(c.versions[0] || '—')}</div>
      </div>
      <button data-copy="${esc(url)}" class="ghost">复制下载链接</button>
    </div>`
  }).join('') || '<div class="hint">还没有配置，下面上传一个</div>'
}

function renderEvents(events) {
  $('#events').innerHTML = events.map((e) => {
    const t = new Date(e.at).toLocaleTimeString('zh-CN', { hour12: false })
    return `<li><time>${t}</time><span><span class="k">${esc(e.device || 'hub')}</span> · ${esc(e.message)}</span></li>`
  }).join('') || '<li class="hint">暂无事件</li>'
}

async function refresh() {
  try {
    const health = await api('/api/health').catch(() => null)
    $('#hub-dot').className = 'dot ' + (health?.ok ? 'on' : 'off')
    $('#hub-ver').textContent = health ? ` v${health.version}` : ''
    $('#hub-addr').textContent = location.host
    $('#hub-meta').textContent = health ? `${health.devices} 台设备已注册` : 'hub 不可达'

    const { devices } = await api('/api/devices')
    state.devices = devices
    renderTopology(devices)
    renderCards(devices)

    const { configs } = await api('/api/configs')
    state.configs = configs
    renderConfigs(configs)

    const { events } = await api('/api/events?limit=80')
    renderEvents(events)
  } catch (e) {
    if (String(e.message) !== 'unauthorized') console.error(e)
  }
}

async function sendCommand(deviceId, action, args) {
  const cmd = await api('/api/commands', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ device: deviceId, action, args })
  })
  toast(`已排队：${action}`)
  setTimeout(refresh, 2500)
  return cmd
}

document.addEventListener('click', async (ev) => {
  const btn = ev.target.closest('button')
  if (!btn) return

  if (btn.dataset.copy) {
    try { await navigator.clipboard.writeText(btn.dataset.copy); toast('链接已复制') }
    catch { prompt('复制这个链接：', btn.dataset.copy) }
    return
  }

  const card = btn.closest('.card')
  if (!card) return
  const id = card.dataset.id
  const act = btn.dataset.act
  if (!act) return
  try {
    if (act === 'set-proxy') {
      const server = card.querySelector('[data-role="proxy"]').value.trim()
      if (!server) return toast('先填 host:port')
      await sendCommand(id, 'set-proxy', { server })
    } else if (act === 'clear-proxy') {
      await sendCommand(id, 'clear-proxy', {})
    } else if (act === 'restart-service') {
      const name = card.querySelector('[data-role="svc"]').value
      await sendCommand(id, 'restart-service', { name })
    } else if (act === 'probe') {
      await sendCommand(id, 'probe', {})
    }
  } catch (e) { toast(String(e.message)) }
})

$('#save-token').onclick = () => {
  state.token = $('#token').value.trim()
  localStorage.setItem('mesh.token', state.token)
  refresh()
}

$('#copy-mobile').onclick = async () => {
  const url = `${location.origin}/m.html${state.token ? `#t=${encodeURIComponent(state.token)}` : ''}`
  try { await navigator.clipboard.writeText(url); toast('手机链接已复制（含 token）') }
  catch { prompt('复制这个链接，在手机浏览器打开：', url) }
}

$('#upload').onsubmit = async (e) => {
  e.preventDefault()
  const name = $('#cfg-name').value.trim()
  const body = $('#cfg-body').value
  if (!name || !body) return
  const r = await fetch(`/api/config/${encodeURIComponent(name)}`, {
    method: 'POST', headers: { 'content-type': 'text/plain', ...authHeaders() }, body
  })
  if (!r.ok) return toast(`上传失败 ${r.status}`)
  $('#cfg-body').value = ''
  toast('已保存新版本')
  refresh()
}

// boot
$('#token').value = state.token
refresh()
state.timer = setInterval(refresh, 4000)
