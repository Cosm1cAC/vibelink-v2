// Client-side unit checks for the phone page (web/m.js), without a browser.
// We only need the pieces that do not touch real DOM layout: API timeout,
// abort, error hints and the takeover event. Run with:
//   node mesh/test/client.mjs
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const code = await readFile(new URL('../web/m.js', import.meta.url), 'utf8')

function element() {
  return {
    textContent: '',
    innerHTML: '',
    value: '',
    disabled: false,
    style: {},
    dataset: {},
    _h: null,
    classList: {
      add() {},
      remove() {},
      toggle() {},
      contains() { return false }
    },
    addEventListener() {},
    focus() {}
  }
}

const elements = new Map()
const document = {
  querySelector(sel) {
    if (!elements.has(sel)) elements.set(sel, element())
    return elements.get(sel)
  },
  addEventListener() {},
  documentElement: { scrollHeight: 0, scrollY: 0, innerHeight: 0 },
  visibilityState: 'visible'
}
const window = {
  __meshDiag: [],
  addEventListener() {},
  scrollTo() {}
}
const storage = new Map()
const localStorage = {
  getItem(k) { return storage.has(k) ? storage.get(k) : null },
  setItem(k, v) { storage.set(k, String(v)) }
}

const sandbox = {
  console,
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
  AbortController,
  TextDecoder,
  URLSearchParams,
  performance,
  crypto: globalThis.crypto,
  document,
  window,
  localStorage,
  location: { hash: '', pathname: '/m.html', search: '' },
  history: { replaceState() {} },
  fetch: async (url, init = {}) => {
    if (url === '/api/ok') {
      return { status: 200, ok: true, json: async () => ({ ok: true }) }
    }
    if (url === '/api/slow') {
      return await new Promise((resolve, reject) => {
        const timer = setTimeout(() => resolve({ status: 200, ok: true, json: async () => ({}) }), 5000)
        init.signal?.addEventListener('abort', () => {
          clearTimeout(timer)
          const err = new Error('aborted')
          err.name = 'AbortError'
          reject(err)
        }, { once: true })
      })
    }
    return { status: 404, ok: false, json: async () => ({ error: 'not found', code: 'NOT_FOUND' }) }
  }
}
sandbox.globalThis = sandbox
const context = vm.createContext(sandbox)
vm.runInContext(code, context, { filename: 'm.js' })

const run = (expr) => vm.runInContext(expr, context)

assert.equal(await run('api("/api/ok").then((b) => b.ok)'), true)

await assert.rejects(
  () => run('api("/api/slow", { timeoutMs: 60 })'),
  (err) => err.code === 'TIMEOUT' && /请求超时/.test(err.message)
)
assert.equal(run('S.inFlight.size'), 0)

const pending = run('api("/api/slow", { timeoutMs: 0 })')
run('abortInflight("test")')
await assert.rejects(pending, (err) => err.code === 'ABORTED' && /请求已取消/.test(err.message))
assert.equal(run('S.inFlight.size'), 0)

assert.match(run('apiError("x", "AGENT_REPLACED").hint'), /另一个 agent/)
assert.match(run('formatErr(apiError("x", "DEVICE_OFFLINE"))'), /心跳已过期/)
assert.equal(run('current(0)'), true)
run('S.epoch = 1')
assert.equal(run('current(0)'), false)
assert.equal(run('current(1)'), true)

// A takeover event must put the stream into a visible "down" state.
run('S.screen = "chat"; S.atBottom = false; S.streamState = "up"; S.streamPhase = "ready"')
run('handleBlock("event: mesh.agent-replaced\\ndata: {\\"activeInstanceId\\":\\"B\\"}\\n")')
assert.equal(run('S.streamState'), 'down')
assert.equal(run('S.streamPhase'), 'agent-replaced')
assert.match(run('S.streamError'), /接管/)

console.log('client: ok')
