// Compile the actual Vue component scripts with synthetic paged HTTP responses.
// These checks exercise state/handlers, not rendered browser pixels.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript } from '@vue/compiler-sfc'
import { mergeMessages, refreshHistory } from '../src/utils/service-history.js'

const rows = (lo, hi) => Array.from({ length: hi - lo + 1 }, (_, i) => ({ id: String(lo + i), sequence: lo + i, createdAt: new Date((lo + i) * 1000).toISOString(), text: String(lo + i) }))
const ok = data => Promise.resolve({ code: '200', data })

function setup(kind) {
  const customer = kind === 'customer'
  const filename = customer ? '../src/components/SupportCustomer.vue' : '../src/views/back/SupportWorkspace.vue'
  const descriptor = parse(fs.readFileSync(new URL(filename, import.meta.url), 'utf8')).descriptor
  const code = compileScript(descriptor, { id: 'history-regression' }).content.replace(/^import .*$/gm, '').replace('export default', 'const component =') + '\nresult=component.setup(props,{expose(){}});'
  const route = vue.reactive({ query: { id: 'S' }, fullPath: '/mall/supportChat?id=S' })
  const state = { total: 200, reads: [], beforeCalls: [] }
  let ui
  const displayed = () => customer ? ui.thread.value?.messages || [] : ui.messages.value
  const request = {
    async get(path, config) {
      if (path === '/unit') return ok([])
      if (path === '/support/overview') return ok({ sessions: [{ id: 'S', unread: 1 }], tickets: [], counts: {} })
      if (path === '/orders/front/page') return ok({ records: [] })
      assert.equal(path, '/support/session')
      const beforeId = config.params.beforeId
      if (beforeId) state.beforeCalls.push(beforeId)
      const hi = beforeId ? Number(beforeId) - 1 : state.total, lo = Math.max(1, hi - 99)
      return ok({ session: { id: 'S', unread: 1 }, messages: rows(lo, hi), hasMore: lo > 1 })
    },
    post(path, payload) {
      assert.equal(path, '/support/mark-read')
      state.reads.push({ id: payload.lastSeenMessageId, displayed: displayed().map(row => row.id) })
      return ok({})
    },
  }
  const scope = vue.effectScope()
  const context = vm.createContext({
    ...vue, props: vue.reactive({ mode: 'supportChat' }), result: null,
    mergeMessages, refreshHistory, request, ServiceUpload: {}, ServiceAttachment: {},
    useRoute: () => route, useRouter: () => ({ push() {} }), onMounted() {}, onUnmounted() {},
    serviceConnection: vue.reactive({ revision: 1 }), isBackendMode: () => true, serviceKey: () => 'synthetic-client-key-long',
    sessionStorage: { getItem: () => JSON.stringify({ id: '101', role: customer ? 'ROLE_USER' : 'ROLE_ADMIN' }) },
    document: { visibilityState: 'visible', querySelector: () => null }, ElMessage: { error() {}, warning() {}, success() {} },
  })
  scope.run(() => vm.runInContext(code, context))
  ui = context.result
  if (!customer) ui.selectedSessionId.value = 'S'
  return {
    state, displayed, stop: () => scope.stop(),
    load: () => customer ? ui.load() : ui.loadSession({ markRead: true }),
    older: () => customer ? ui.older() : ui.loadOlder(),
    hasMore: () => customer ? ui.historyMore.value : ui.sessionHasMore.value,
  }
}

for (const kind of ['customer', 'workspace']) {
  const h = setup(kind)
  try {
    await h.load()
    await h.older()
    assert.equal(h.displayed().length, 200)
    assert.equal(h.hasMore(), false)
    h.state.total = 350
    await h.load()
    assert.equal(h.displayed().length, 350)
    assert.deepEqual(Array.from(h.state.reads.at(-1).displayed), rows(1, 350).map(row => row.id))
    assert.equal(h.state.reads.at(-1).id, '350')
    console.log('PASS', kind, 'fills the original 150-message refresh gap before advancing read cursor')

    h.state.total = 1600
    const readCount = h.state.reads.length, beforeCount = h.state.beforeCalls.length
    await h.load()
    assert.equal(h.state.beforeCalls.length - beforeCount, 10)
    assert.equal(h.displayed().length, 100)
    assert.equal(h.hasMore(), true)
    assert.equal(h.state.reads.length, readCount)
    await h.load()
    await h.load()
    assert.equal(h.state.reads.length, readCount)
    await h.older()
    await h.load()
    assert.equal(h.state.reads.length, readCount)
    console.log('PASS', kind, 'keeps read cursor blocked across repeated polling and partially recovered history')

    let pages = 0
    while (h.hasMore()) {
      assert.ok(pages++ < 20, 'older pagination must terminate')
      await h.older()
    }
    await h.load()
    assert.equal(h.state.reads.length, readCount + 1)
    assert.equal(h.state.reads.at(-1).id, '1600')
    assert.deepEqual(Array.from(h.state.reads.at(-1).displayed), rows(1, 1600).map(row => row.id))
    console.log('PASS', kind, 'resumes read cursor only after all retained history is recovered')
  } finally {
    h.stop()
  }
}
