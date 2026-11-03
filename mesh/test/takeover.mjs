// End-to-end takeover test: temp hub + fake agent control plane + phone SSE.
// Zero dependencies; run with `node mesh/test/takeover.mjs`.
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { startControl } from '../agent/control.ts'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const allocatedPorts = new Set()

async function freePort() {
  for (;;) {
    const srv = createServer()
    srv.listen(0, '127.0.0.1')
    await once(srv, 'listening')
    const port = srv.address().port
    await new Promise((r) => srv.close(r))
    if (!allocatedPorts.has(port)) {
      allocatedPorts.add(port)
      return port
    }
  }
}

async function waitFor(fn, timeoutMs, label) {
  const until = Date.now() + timeoutMs
  for (;;) {
    const value = await fn()
    if (value) return value
    if (Date.now() > until) throw new Error(`timeout waiting for ${label}`)
    await sleep(100)
  }
}

async function startFakeAgent(port) {
  const server = createServer((req, res) => {
    if (req.url?.startsWith('/events')) {
      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-store',
        connection: 'keep-alive'
      })
      res.write(`event: server.connected\ndata: ${JSON.stringify({ at: Date.now(), protocol: 2 })}\n\n`)
      const timer = setInterval(() => {
        try { res.write(': heartbeat\n\n') } catch { /* closed */ }
      }, 1000)
      req.on('close', () => clearInterval(timer))
      return
    }
    if (req.url?.startsWith('/health') || req.url?.startsWith('/status')) {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({
        ok: true,
        protocol: 2,
        instance: { id: 'fake', startedAt: 1, state: 'connected' },
        events: 1,
        inflight: 0,
        opencode: { api: 'v2' }
      }))
      return
    }
    res.writeHead(404)
    res.end()
  })
  server.listen(port, '127.0.0.1')
  await once(server, 'listening')
  return {
    close: () => new Promise((r) => server.close(r))
  }
}

async function startHub(port, dataDir) {
  let stderr = ''
  const child = spawn(process.execPath, ['hub/server.ts'], {
    cwd: ROOT,
    env: { ...process.env, MESH_PORT: String(port), MESH_DATA: dataDir },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  child.stderr.on('data', (d) => { stderr += d.toString() })
  child.stdout.on('data', () => {})
  const base = `http://127.0.0.1:${port}`
  try {
    await waitFor(async () => {
      try {
        const r = await fetch(`${base}/api/health`)
        return r.ok
      } catch {
        return false
      }
    }, 10_000, 'hub health')
  } catch (err) {
    child.kill()
    throw new Error(`${err.message}\nhub stderr:\n${stderr}`)
  }
  return { child, base, stderr: () => stderr }
}

async function startAgent(base, token, device, controlPort, reclaimMs = '0') {
  const child = spawn(process.execPath, ['agent/agent.ts'], {
    cwd: ROOT,
    env: {
      ...process.env,
      MESH_HUB: base,
      MESH_TOKEN: token,
      MESH_DEVICE: device,
      MESH_CONTROL_PORT: String(controlPort),
      OPENCODE_PORT: '1',
      MESH_RECLAIM_MS: reclaimMs
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  let out = ''
  let err = ''
  child.stdout.on('data', (d) => { out += d.toString() })
  child.stderr.on('data', (d) => { err += d.toString() })
  const instanceId = await waitFor(() => {
    const m = /instance ([0-9a-f-]{36})/.exec(out)
    return m ? m[1] : ''
  }, 10_000, `${device} instance id`)
  return { child, instanceId, out: () => out, err: () => err }
}

async function agentTakeover(base, token, { device = 'dupe', reclaimMs = '0' } = {}) {
  const p1 = await freePort()
  const p2 = await freePort()
  const a1 = await startAgent(base, token, device, p1, reclaimMs)
  const headers = { 'x-mesh-token': token, 'x-mesh-protocol': '2' }
  const readDevice = async () => {
    const r = await (await fetch(`${base}/api/devices`, { headers })).json()
    return r.devices.find((d) => d.id === device)
  }
  try {
    await waitFor(async () => (await readDevice())?.instanceId === a1.instanceId, 10_000, 'agent1 active')
    await sleep(250)
    const a2 = await startAgent(base, token, device, p2)
    try {
      await waitFor(async () => (await readDevice())?.instanceId === a2.instanceId, 10_000, 'agent2 takeover')
      // Default: the older instance parks. With MESH_RECLAIM_MS it stands by
      // instead, but the hub's newer-wins rule keeps it from thrashing.
      const expected = reclaimMs === '0' ? /parked: another instance owns/ : /standing by/
      await waitFor(
        () => expected.test(a1.out() + a1.err()),
        20_000,
        reclaimMs === '0' ? 'agent1 parked' : 'agent1 standby'
      )
      console.log(reclaimMs === '0' ? 'agent takeover: ok' : 'agent standby: ok')
    } finally {
      a2.child.kill()
      await Promise.race([once(a2.child, 'exit'), sleep(2000)]).catch(() => {})
    }
  } finally {
    a1.child.kill()
    await Promise.race([once(a1.child, 'exit'), sleep(2000)]).catch(() => {})
  }
}

async function main() {
  const hubPort = await freePort()
  const fakePort = await freePort()
  const dataDir = await mkdtemp(join(tmpdir(), 'mesh-takeover-'))
  const fake = await startFakeAgent(fakePort)
  let hub
  try {
    hub = await startHub(hubPort, dataDir)
  } catch (err) {
    await fake.close()
    await rm(dataDir, { recursive: true, force: true }).catch(() => {})
    throw err
  }
  let token
  try {
    token = await waitFor(
      async () => {
        try { return (await readFile(join(dataDir, 'token.txt'), 'utf8')).trim() } catch { return '' }
      },
      5_000,
      'hub token'
    )
  } catch (err) {
    hub.child.kill()
    await fake.close()
    await rm(dataDir, { recursive: true, force: true }).catch(() => {})
    throw err
  }

  const headers = { 'x-mesh-token': token, 'x-mesh-protocol': '2' }
  const ingest = async (instanceId, instanceStartedAt, port = fakePort, id = 'dev') => {
    const r = await fetch(`${hub.base}/api/ingest`, {
      method: 'POST',
      headers: { ...headers, 'content-type': 'application/json' },
      body: JSON.stringify({
        id,
        hostname: 'test',
        platform: 'test',
        arch: 'x64',
        agentVersion: '0.3.0',
        protocolVersion: 2,
        instanceId,
        instanceStartedAt,
        agentState: 'connected',
        control: { port },
        ips: ['127.0.0.1'],
        ports: {},
        mem: { free: 1, total: 2 },
        uptimeSec: 1
      })
    })
    return { status: r.status, body: await r.json() }
  }
  const commands = async (instanceId, id = 'dev') => {
    const r = await fetch(`${hub.base}/api/commands?device=${encodeURIComponent(id)}`, {
      headers: { ...headers, 'x-mesh-instance': instanceId }
    })
    return { status: r.status, body: await r.json() }
  }

  try {
    assert.equal((await ingest('A', 1000)).status, 200)
    assert.equal((await ingest('A', 1000)).status, 200)

    // /status is proxied through the same allowlist as /health.
    const status = await fetch(`${hub.base}/api/oc/dev/status`, { headers })
    assert.equal(status.status, 200)
    assert.equal((await status.json()).instance.id, 'fake')

    // Phone attaches before the takeover. The fake agent sends
    // server.connected immediately, so the hub registers the SSE stream.
    const ctrl = new AbortController()
    const sse = await fetch(`${hub.base}/api/oc/dev/events`, {
      headers: { ...headers, accept: 'text/event-stream' },
      signal: ctrl.signal
    })
    assert.equal(sse.status, 200)
    const reader = sse.body.getReader()
    const decoder = new TextDecoder()
    let sseText = ''
    const readAll = (async () => {
      for (;;) {
        const { value, done } = await reader.read()
        if (done) return
        sseText += decoder.decode(value, { stream: true })
      }
    })()
    await waitFor(() => sseText.includes('server.connected'), 3_000, 'server.connected')

    // B is newer: it wins.
    assert.equal((await ingest('B', 2000)).status, 200)
    await waitFor(() => sseText.includes('mesh.agent-replaced'), 5_000, 'agent-replaced event')
    assert.match(sseText, /event: mesh\.agent-replaced/)
    assert.match(sseText, /"activeInstanceId":"B"/)
    ctrl.abort()
    await readAll.catch(() => {})

    // A zombie cannot take the device back.
    const stale = await ingest('A', 1000)
    assert.equal(stale.status, 409)
    assert.equal(stale.body.code, 'AGENT_REPLACED')
    assert.equal(stale.body.activeInstanceId, 'B')

    // Command queue is bound to the active instance too.
    assert.equal((await commands('A')).status, 409)
    assert.equal((await commands('B')).status, 200)

    // A genuinely newer process can take over again.
    assert.equal((await ingest('C', 3000)).status, 200)
    assert.equal((await ingest('B', 2000)).status, 409)
    const devices = await (await fetch(`${hub.base}/api/devices`, { headers })).json()
    assert.equal(devices.devices.find((d) => d.id === 'dev').instanceId, 'C')

    // Legacy agents (no instanceId) can own a device only until a managed
    // instance arrives; after that they get 409 like any other stale writer.
    assert.equal((await ingest(null, 500, fakePort, 'legacy')).status, 200)
    assert.equal((await ingest('D', 600, fakePort, 'legacy')).status, 200)
    assert.equal((await ingest(null, 500, fakePort, 'legacy')).status, 409)
    assert.equal((await commands(null, 'legacy')).status, 409)
    assert.equal((await commands('D', 'legacy')).status, 200)

    console.log('takeover: ok')
    await agentTakeover(hub.base, token)
    await agentTakeover(hub.base, token, { device: 'dupe2', reclaimMs: '1000' })
  } finally {
    await fake.close()
    hub.child.kill()
    await Promise.race([once(hub.child, 'exit'), sleep(2000)]).catch(() => {})
    await rm(dataDir, { recursive: true, force: true }).catch(() => {})
  }
}

async function controlStatus() {
  const control = startControl({
    host: '127.0.0.1',
    port: 0,
    token: 'test-token',
    target: { baseUrl: 'http://127.0.0.1:1', password: '' },
    instance: { id: 'inst-test', startedAt: 123 },
    getStatus: () => ({
      state: 'connected',
      lastError: null,
      lastIngestAt: 456,
      connectedAt: 789
    }),
    log: () => {}
  })
  await once(control.server, 'listening')
  const port = control.server.address().port
  try {
    const r = await fetch(`http://127.0.0.1:${port}/status`, {
      headers: { 'x-mesh-token': 'test-token' }
    })
    assert.equal(r.status, 200)
    const body = await r.json()
    assert.equal(body.instance.id, 'inst-test')
    assert.equal(body.instance.startedAt, 123)
    assert.equal(body.instance.state, 'connected')
    assert.equal(body.instance.lastIngestAt, 456)
    console.log('control status: ok')
  } finally {
    control.close()
  }
}

main()
  .then(controlStatus)
  .catch((err) => {
    console.error(err)
    process.exitCode = 1
  })
