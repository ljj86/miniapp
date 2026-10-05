/**
 * Current v7 regression checks for SupportCustomer and ServiceUpload async races.
 * Retains applicable customer lifecycle/draft cases. The three v6 single-image
 * cases now exercise ServiceUpload; busy uploads deliberately reject a second
 * batch, replacing the removed single-image "latest selection wins" behavior.
 * Uses compiled current SFC scripts and deferred dependencies, not browser DOM,
 * actual FileReader/HTTP, or a replay of the historical v6 verification result.
 *
 * Run:
 *   node customer-races.test.mjs [checkout-root]
 */
import fs from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import path from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'

const checkout = path.resolve(process.argv[2] || process.cwd())
const require = createRequire(path.join(checkout, 'package.json'))
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const { mergeMessages, refreshHistory } = await import(pathToFileURL(path.join(checkout, 'src/utils/service-history.js')).href)
const filename = path.join(checkout, 'src/components/SupportCustomer.vue')
const source = fs.readFileSync(filename, 'utf8')
const descriptor = parse(source).descriptor
const script = compileScript(descriptor, { id: 'audit-fixes' })
const template = compileTemplate({ source: descriptor.template.content, filename, id: 'audit-fixes', compilerOptions: { bindingMetadata: script.bindings } })
assert.equal(template.errors.length, 0)
const code = script.content.replace(/^import .*$/gm, '').replace('export default', 'const component =') + '\nresult=component.setup(props,{expose(){}});'
const ok = data => Promise.resolve({ code: '200', data })
const deferred = () => {
  let resolve
  const promise = new Promise(r => resolve = r)
  return { promise, resolve: data => resolve({ code: '200', data }) }
}

function setup(mode = 'supportChat') {
  const props = vue.reactive({ mode })
  const route = vue.reactive({ query: { id: 'A' }, fullPath: '/mall/' + mode + '?id=A' })
  const state = {
    account: JSON.stringify({ id: 1, role: 'ROLE_USER' }),
    readers: [], calls: [], routes: [], events: [], removed: [], timers: [], cleared: [], notes: [],
    mounted: null, unmount: null, post: () => ok({}), get: null,
  }
  const ticket = { id: 'T1', version: 1, status: 'OPEN', replies: [], history: [] }
  const request = {
    get: (path, config) => {
      state.calls.push(['get', path, config])
      return state.get ? state.get(path, config)
        : path === '/unit' ? ok([{ id: 1, nickname: '门店' }])
        : path === '/support/overview' ? ok({ sessions: [], tickets: [], counts: {} })
        : path === '/orders/front/page' ? ok({ records: [] })
        : path === '/support/session' ? ok({ session: { id: config.params.id }, messages: [{ id: '100', text: 'existing synthetic message' }], hasMore: false })
        : ok(ticket)
    },
    post: (path, payload) => {
      state.calls.push(['post', path, payload])
      return state.post(path, payload)
    },
  }
  const document = {
    visibilityState: 'visible',
    querySelector: () => ({ scrollTo() {} }),
    addEventListener: (...args) => state.events.push(['document', ...args]),
    removeEventListener: (...args) => state.removed.push(['document', ...args]),
  }
  const scope = vue.effectScope()
  const context = vm.createContext({
    ...vue, props, result: null,
    onMounted: fn => state.mounted = fn,
    onUnmounted: fn => state.unmount = fn,
    useRoute: () => route,
    useRouter: () => ({ push: value => state.routes.push(value) }),
    request,
    serviceConnection: vue.reactive({ revision: 0, mode: 'demo' }),
    isBackendMode: () => false,
    serviceKey: prefix => prefix + '-synthetic-deferred-test-key',
    mergeMessages, refreshHistory, ServiceUpload: {}, ServiceAttachment: {},
    ElMessage: {
      warning: value => state.notes.push(value),
      error: value => state.notes.push(value),
      success: value => state.notes.push(value),
    },
    sessionStorage: { getItem: () => state.account },
    FileReader: class {
      constructor() { state.readers.push(this) }
      readAsDataURL() {}
    },
    document,
    window: {
      addEventListener: (...args) => state.events.push(['window', ...args]),
      removeEventListener: (...args) => state.removed.push(['window', ...args]),
    },
    setInterval: (fn, ms) => { state.timers.push({ fn, ms }); return state.timers.length },
    clearInterval: id => state.cleared.push(id),
    ChatDotRound: {}, Document: {}, ArrowRight: {}, Shop: {}, Refresh: {}, Picture: {}, Close: {}, Plus: {}, Clock: {}, InfoFilled: {},
  })
  scope.run(() => vm.runInContext(code, context))
  return {
    ui: context.result, state, route, props, document,
    stop: () => scope.stop(),
    nav: async (id, mode = props.mode) => {
      props.mode = mode
      route.query = { id }
      route.fullPath = '/mall/' + mode + '?id=' + id
      await vue.nextTick()
      await Promise.resolve()
    },
  }
}

const uploadFilename = path.join(checkout, 'src/components/ServiceUpload.vue')
const uploadDescriptor = parse(fs.readFileSync(uploadFilename, 'utf8')).descriptor
const uploadScript = compileScript(uploadDescriptor, { id: 'upload-races-v7' })
const uploadTemplate = compileTemplate({ source: uploadDescriptor.template.content, filename: uploadFilename, id: 'upload-races-v7', compilerOptions: { bindingMetadata: uploadScript.bindings } })
assert.equal(uploadTemplate.errors.length, 0)
const uploadCode = uploadScript.content.replace(/^import .*$/gm, '').replace('export default', 'const component =') + '\nresult=component.setup(props,{expose(){},emit:recordEmit});'
function setupUpload() {
  const props = vue.reactive({ modelValue: [], disabled: false, contextKey: 'A' })
  const serviceConnection = vue.reactive({ revision: 0, mode: 'demo' })
  const state = { reads: [], emitted: [], posted: [], unmount: null }
  const scope = vue.effectScope()
  const context = vm.createContext({
    ...vue, props, result: null, serviceConnection,
    onUnmounted: fn => state.unmount = fn,
    recordEmit: (name, value) => state.emitted.push([name, value]),
    readServiceFile: file => new Promise(resolve => state.reads.push({ file, resolve })),
    request: { post: async (url, payload) => { state.posted.push([url, payload]); return { code: '200', data: { id: '101', name: payload.name, mime: payload.mime, size: 1, securityStatus: 'UNSCANNED_SIMULATION' } } } },
    serviceKey: () => 'synthetic-upload-race-key',
    SERVICE_FILE_TYPES: ['image/png'], SERVICE_COMMAND_LIMIT: 1024 * 1024,
    ElMessage: { warning() {} }, Paperclip: {}, Close: {},
  })
  scope.run(() => vm.runInContext(uploadCode, context))
  return {
    ui: context.result, state, props, serviceConnection,
    file: name => ({ target: { files: [{ type: 'image/png', size: 1, name }], value: 'x' } }),
    finishRead: index => { const read = state.reads[index]; read.resolve({ name: read.file.name, mime: 'image/png', base64: 'YQ==' }) },
    stop: () => scope.stop(),
  }
}

let passed = 0
async function test(name, fn) {
  await fn()
  console.log('PASS:', name)
  passed++
}

await test('ServiceUpload pending read ignored after A→B context change', async () => {
  const h = setupUpload(), pending = h.ui.select(h.file('A.png'))
  h.props.contextKey = 'B'
  await vue.nextTick()
  h.finishRead(0)
  await pending
  assert.equal(h.state.posted.length, 0)
  assert.equal(h.state.emitted.filter(([name]) => name === 'update:modelValue').length, 0)
  assert.equal(h.ui.busy.value, false)
  h.stop()
})

await test('ServiceUpload busy state prevents concurrent second file selection', async () => {
  const h = setupUpload(), first = h.ui.select(h.file('first.png'))
  await h.ui.select(h.file('second.png'))
  assert.equal(h.state.reads.length, 1)
  assert.equal(h.ui.busy.value, true)
  h.finishRead(0)
  await first
  assert.equal(h.state.posted.length, 1)
  const updates = h.state.emitted.filter(([name]) => name === 'update:modelValue')
  assert.equal(updates.length, 1)
  assert.equal(updates[0][1][0].name, 'first.png')
  assert.equal(h.ui.busy.value, false)
  h.stop()
})

await test('ServiceUpload pending read ignored after unmount', async () => {
  const h = setupUpload(), pending = h.ui.select(h.file('A.png'))
  h.state.unmount()
  h.finishRead(0)
  await pending
  assert.equal(h.state.posted.length, 0)
  assert.equal(h.state.emitted.filter(([name]) => name === 'update:modelValue').length, 0)
  h.stop()
})

await test('send completion preserves changed same-route draft', async () => {
  const h = setup(), d = deferred()
  h.state.post = path => path.endsWith('send-message') ? d.promise : ok({})
  h.ui.text.value = 'sent'
  await vue.nextTick()
  const pending = h.ui.send()
  h.ui.text.value = 'next unsent'
  await vue.nextTick()
  d.resolve({ id: 'M1' })
  await pending
  assert.equal(h.ui.text.value, 'next unsent')
  h.stop()
})

await test('send completion preserves newer route draft', async () => {
  const h = setup(), d = deferred()
  h.state.post = path => path.endsWith('send-message') ? d.promise : ok({})
  h.ui.text.value = 'sent'
  await vue.nextTick()
  const pending = h.ui.send()
  await h.nav('B')
  h.ui.text.value = 'B unsent'
  await vue.nextTick()
  d.resolve({ id: 'M1' })
  await pending
  assert.equal(h.ui.text.value, 'B unsent')
  h.stop()
})

await test('reply completion preserves changed same-route draft', async () => {
  const h = setup('supportTicket'), d = deferred()
  h.ui.ticket.value = { id: 'T1', version: 1 }
  h.state.post = () => d.promise
  h.ui.text.value = 'sent'
  await vue.nextTick()
  const pending = h.ui.replyTicket()
  h.ui.text.value = 'next reply'
  await vue.nextTick()
  d.resolve({ id: 'R1' })
  await pending
  assert.equal(h.ui.text.value, 'next reply')
  h.stop()
})

await test('create completion cannot navigate after leaving form', async () => {
  const h = setup('supportNew'), d = deferred()
  h.state.post = () => d.promise
  h.ui.form.value = { unitId: 1, title: 'title', content: 'body', priority: 'NORMAL', orderId: '' }
  await vue.nextTick()
  const pending = h.ui.submitTicket()
  await h.nav('B', 'supportTickets')
  d.resolve({ id: 'T1' })
  await pending
  assert.equal(h.state.routes.length, 0)
  h.stop()
})

await test('create completion preserves changed same-route form without navigation', async () => {
  const h = setup('supportNew'), d = deferred()
  h.state.post = () => d.promise
  h.ui.form.value = { unitId: 1, title: 'title', content: 'body', priority: 'NORMAL', orderId: '' }
  await vue.nextTick()
  const pending = h.ui.submitTicket()
  h.ui.form.value.content = 'next draft'
  await vue.nextTick()
  d.resolve({ id: 'T1' })
  await pending
  assert.equal(h.state.routes.length, 0)
  assert.equal(h.ui.form.value.content, 'next draft')
  h.stop()
})

await test('session creation completion cannot navigate after leaving', async () => {
  const h = setup('support'), d = deferred()
  h.state.post = () => d.promise
  const pending = h.ui.start({ id: 1 })
  await h.nav('B', 'supportTickets')
  d.resolve({ id: 'S1' })
  await pending
  assert.equal(h.state.routes.length, 0)
  h.stop()
})

await test('actor change invalidates pending action UI completion', async () => {
  const h = setup(), d = deferred()
  h.state.post = () => d.promise
  h.ui.text.value = 'sent'
  await vue.nextTick()
  const pending = h.ui.send()
  h.state.account = JSON.stringify({ id: 2, role: 'ROLE_USER' })
  h.ui.text.value = 'new actor draft'
  d.resolve({ id: 'M1' })
  await pending
  assert.equal(h.ui.text.value, 'new actor draft')
  h.stop()
})

await test('old action completion cannot release newer action busy state', async () => {
  const h = setup(), first = deferred(), second = deferred()
  h.state.post = (path, payload) => path.endsWith('send-message') ? (payload.sessionId === 'A' ? first.promise : second.promise) : ok({})
  h.ui.text.value = 'sent A'
  await vue.nextTick()
  const pendingA = h.ui.send()
  await h.nav('B')
  h.ui.text.value = 'sent B'
  await vue.nextTick()
  const pendingB = h.ui.send()
  first.resolve({ id: 'M1' })
  await pendingA
  assert.equal(h.ui.busy.value, true)
  second.resolve({ id: 'M2' })
  await pendingB
  assert.equal(h.ui.busy.value, false)
  h.stop()
})

await test('hidden load does not mark read; visible load does', async () => {
  const h = setup()
  h.document.visibilityState = 'hidden'
  await h.ui.load()
  assert.equal(h.state.calls.filter(call => call[1] === '/support/mark-read').length, 0)
  h.document.visibilityState = 'visible'
  await h.ui.load()
  assert.equal(h.state.calls.filter(call => call[1] === '/support/mark-read').length, 1)
  h.stop()
})

await test('hidden interval skips fetch, focus/visibility listeners removed on unmount', async () => {
  const h = setup()
  await h.state.mounted()
  assert.equal(h.state.timers.length, 1)
  h.document.visibilityState = 'hidden'
  const before = h.state.calls.length
  h.state.timers[0].fn()
  await Promise.resolve()
  assert.equal(h.state.calls.length, before)
  assert.deepEqual(h.state.events.map(event => event[1]).sort(), ['focus', 'storage', 'visibilitychange'])
  h.state.unmount()
  assert.deepEqual(h.state.removed.map(event => event[1]).sort(), ['focus', 'storage', 'visibilitychange'])
  assert.equal(h.state.cleared[0], 1)
  h.stop()
})

console.log(`All ${passed} current v7 deferred/lifecycle checks passed; SupportCustomer and ServiceUpload SFC compilation passed. Scope: component scripts with deferred dependencies, not browser pixels or actual HTTP/FileReader.`)
