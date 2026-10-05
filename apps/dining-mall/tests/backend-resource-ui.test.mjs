// Actual compiled Vue handlers -> loopback Java servlet; fresh synthetic state only.
// This test validates DOM behavior and downloaded bytes, not browser pixels.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'
import path from 'node:path'

const root = process.env.MALL_FRONTEND_ROOT || process.cwd()
const require = createRequire(path.join(root, 'package.json'))
const { JSDOM } = require('jsdom')
const baseUrl = process.env.SHOP_API_BASE
assert.ok(baseUrl, 'Run through verification/with-support-http-fixture.py')
const dom = new JSDOM('<!doctype html><html><body><div id="app"></div></body></html>', { url: 'https://mall-test.invalid/#/mall/home', pretendToBeVisual: true })
for (const k of ['window', 'document', 'location', 'history', 'localStorage', 'sessionStorage', 'Node', 'Document', 'ShadowRoot', 'Element', 'HTMLElement', 'SVGElement', 'HTMLInputElement', 'HTMLTextAreaElement', 'HTMLFormElement', 'Event', 'CustomEvent', 'MouseEvent', 'KeyboardEvent', 'MutationObserver', 'DOMParser', 'getComputedStyle']) {
  Object.defineProperty(globalThis, k, { value: k === 'getComputedStyle' ? dom.window[k].bind(dom.window) : dom.window[k], configurable: true })
}
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true })
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} })
Element.prototype.scrollTo = function () {}
dom.window.confirm = () => true // Only confirms reversible archive/discard within fresh fixture state.

const trace = [], downloads = [], pendingTrace = new Set()
const nativeFetch = globalThis.fetch
const scrub = value => {
  if (Array.isArray(value)) return value.map(scrub)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).filter(([k, v]) => typeof v === 'boolean' || !/token|secret|authorization|challenge|credential/i.test(k)).map(([k, v]) => [k, k === 'base64' ? `[base64 omitted, ${v.length} characters]` : scrub(v)]))
}
// Transparent observation only: every request still uses native fetch and the actual servlet.
globalThis.fetch = async (url, options) => {
  const response = await nativeFetch(url, options)
  if (String(url).startsWith(baseUrl) && !String(url).includes('/auth/')) {
    const record = { method: options?.method || 'GET', url: String(url).slice(baseUrl.length), status: response.status }
    trace.push(record)
    const pending = response.clone().json().then(data => { record.dto = scrub(data) }).catch(() => {}).finally(() => pendingTrace.delete(pending))
    pendingTrace.add(pending)
  }
  return response
}
// Keep native Node Blob and URL.createObjectURL. Capture only the final anchor download.
const anchorClick = dom.window.HTMLAnchorElement.prototype.click
dom.window.HTMLAnchorElement.prototype.click = function () {
  if (this.download && this.href.startsWith('blob:')) {
    const name = this.download, url = this.href
    const item = { name, url, promise: nativeFetch(url).then(async response => ({ mime: response.headers.get('content-type'), bytes: Buffer.from(await response.arrayBuffer()) })) }
    downloads.push(item)
  } else anchorClick.call(this)
}

const { createServiceHttpClient } = await import(pathToFileURL(path.join(root, 'src/utils/service-http.js')).href)
const h = await import(pathToFileURL(path.join(root, '.test-build/harness.mjs')).href)
const ok = result => { assert.equal(result.code, '200', result.backendCode + ': ' + result.msg); return result.data }
async function authenticate(phone) {
  let token = ''
  const client = createServiceHttpClient({ baseUrl, tokenProvider: () => token })
  const challenge = ok(await client.post('/auth/sms-challenges', { phone, purpose: 'LOGIN' }, { anonymous: true }))
  const session = ok(await client.post('/auth/sessions', { provider: 'PHONE_OTP', phone, challengeId: challenge.challengeId, code: '246810' }, { anonymous: true }))
  token = session.accessToken
  assert.ok(token)
  return { client, token }
}
const [customer, platform] = await Promise.all(['SIM-USER-001', 'SIM-SUPPORT-001'].map(authenticate))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(predicate, label, timeout = 6000) {
  const stop = Date.now() + timeout
  while (Date.now() < stop) { await h.nextTick(); if (predicate()) return; await sleep(25) }
  throw new Error('Timed out: ' + label + '\nDOM excerpt: ' + document.body.textContent.slice(-4500))
}
function el(selector, scope = document) { const result = scope.querySelector(selector); assert.ok(result, 'Missing selector: ' + selector); return result }
async function click(selector, scope = document) { await h.nextTick(); const target = el(selector, scope); assert.ok(!target.disabled && !target.matches(':disabled'), 'Disabled selector: ' + selector); target.click(); await h.nextTick() }
async function clickText(text, scope = document) {
  await h.nextTick()
  const target = [...scope.querySelectorAll('button')].find(button => button.textContent.trim() === text)
  assert.ok(target, 'Missing button text: ' + text)
  assert.ok(!target.disabled && !target.matches(':disabled'), 'Disabled button text: ' + text)
  target.click(); await h.nextTick()
}
function input(selector, value, scope = document) { const target = el(selector, scope); target.value = value; target.dispatchEvent(new Event('input', { bubbles: true })); target.dispatchEvent(new Event('change', { bubbles: true })); return target }
async function route(url) { await h.router.push(url); await h.nextTick(); await sleep(30) }
async function as(actor, role) {
  await route('/login')
  const context = await h.attachServiceSession({ baseUrl, token: actor.token })
  assert.equal(context.actor.role, role)
  assert.equal(h.serviceConnection.mode, 'backend')
  assert.equal(h.serviceConnection.connected, true)
  return context
}
function portrait(url) { assert.ok(document.querySelector('.native-device'), url + ' missing .native-device'); assert.ok(!document.querySelector('.back-container'), url + ' entered backend shell') }
function recent(method, url) { return trace.filter(item => item.method === method && item.url.split('?')[0] === url).at(-1) }
async function dto(method, url) { await Promise.all([...pendingTrace]); const result = recent(method, url); assert.ok(result, 'No real HTTP request: ' + method + ' ' + url); assert.equal(result.status, 200, JSON.stringify(result)); return result.dto.data }
const results = []
async function check(name, run) {
  const start = trace.length
  try { await run(); results.push({ name, status: 'PASS', httpRequests: trace.length - start }); console.log('PASS ' + name) }
  catch (error) { await Promise.all([...pendingTrace]); results.push({ name, status: 'FAIL', error: error.message, httpRequests: trace.length - start }); console.error('FAIL ' + name + '\n' + error.stack + '\nRecent actual HTTP DTOs: ' + JSON.stringify(trace.slice(-8))) }
}
await h.attachServiceSession({ baseUrl, token: customer.token })
const app = await h.mount()
let customManual, knowledge, update
try {
  await check('Customer built-in manual catalog, page next/previous, PDF and PNG bytes, portrait shell', async () => {
    await route('/mall/manuals')
    await until(() => document.querySelector('[data-manual-id="1"]'), '[data-manual-id="1"] from actual Java manuals')
    portrait('/mall/manuals')
    const manuals = await dto('GET', '/support/manuals')
    assert.equal(manuals.length, 3)
    await click('[data-manual-id="1"]')
    await until(() => document.querySelector('[data-testid="manual-page-image"]'), 'first manual image blob')
    assert.equal(h.router.currentRoute.value.path, '/mall/manual')
    portrait('/mall/manual')
    assert.ok(el('[data-testid="manual-prev"]').disabled)
    const pageOne = el('[data-testid="manual-page-image"]').src
    const png = Buffer.from(await (await nativeFetch(pageOne)).arrayBuffer())
    assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a')
    await click('[data-testid="manual-next"]')
    await until(() => document.querySelector('[data-testid="manual-page-image"]')?.alt.includes('第 2 页'), 'manual page 2')
    assert.ok(el('[data-testid="manual-next"]').disabled)
    assert.equal((await dto('GET', '/support/manual-asset')).page, 2)
    await click('[data-testid="manual-prev"]')
    await until(() => document.querySelector('[data-testid="manual-page-image"]')?.alt.includes('第 1 页'), 'manual page 1 after previous')
    const count = downloads.length
    await click('[data-testid="manual-download-pdf"]')
    await until(() => downloads.length > count, 'PDF anchor download')
    const download = downloads.at(-1), file = await download.promise, asset = await dto('GET', '/support/manual-asset')
    assert.equal(file.mime, 'application/pdf')
    assert.equal(file.bytes.subarray(0, 5).toString(), '%PDF-')
    assert.ok(download.name.endsWith('.pdf'))
    assert.equal(file.bytes.length, asset.size)
    assert.equal(createHash('sha256').update(file.bytes).digest('hex'), asset.sha256)
    await clickText('查看文字版')
    assert.ok(el('[data-testid="manual-text"]').textContent.trim())
  })

  await check('Platform resource UI creates/edits manual, history snapshots, and immutable built-ins', async () => {
    await as(platform, 'ROLE_ADMIN')
    await route('/platform/materials')
    await until(() => document.querySelector('[data-resource-id="1"]'), 'platform resource table')
    await click('[data-resource-id="1"]')
    assert.ok(el('[data-testid="resource-editor"] fieldset').disabled)
    assert.equal(document.querySelector('[data-testid="resource-save"]'), null)
    assert.equal(document.querySelector('[data-testid="resource-archive"]'), null)
    const before = ok(await platform.client.get('/support/resource', { params: { id: '1' } }))
    const denied = await platform.client.post('/support/resources/update', { version: before.version, kind: before.kind, title: 'Synthetic immutable check', content: 'Must remain unchanged', audience: before.audience }, { params: { id: '1' } })
    assert.notEqual(denied.code, '200')
    assert.deepEqual(ok(await platform.client.get('/support/resource', { params: { id: '1' } })), before)
    await click('[data-testid="resource-create"]')
    input('[data-testid="resource-title"]', 'HTTP DOM 合成文字手册')
    input('[data-testid="resource-kind"]', 'MANUAL')
    input('[data-testid="resource-audience"]', 'CUSTOMER')
    input('[data-testid="resource-content"]', '文字手册初版\n<b>保持原文</b> & synthetic-only')
    await click('[data-testid="resource-save"]')
    await until(() => document.querySelector('.success-banner')?.textContent.includes('已创建'), 'manual creation notice')
    customManual = await dto('POST', '/support/resources/create')
    assert.equal(customManual.kind, 'MANUAL'); assert.equal(customManual.version, 1)
    await until(() => document.querySelectorAll('[data-testid="resource-history"] li').length === 1, 'created version history')
    input('[data-testid="resource-content"]', '文字手册修订版\n<b>保持原文</b> & synthetic-only')
    await click('[data-testid="resource-save"]')
    await until(() => document.querySelector('.success-banner')?.textContent.includes('当前版本 2'), 'manual v2 save notice')
    customManual = await dto('POST', '/support/resources/update')
    assert.equal(customManual.version, 2)
    await until(() => document.querySelectorAll('[data-testid="resource-history"] li').length === 2, 'both history entries')
    await clickText('查看快照', el('[data-testid="resource-history"] li'))
    assert.ok(el('.history-snapshot p').textContent.includes('<b>保持原文</b>'))
    assert.equal(el('.history-snapshot p').querySelector('b'), null)
    const historyRows = ok(await platform.client.get('/support/resources/history', { params: { id: customManual.id } }))
    assert.deepEqual(historyRows.map(row => row.action).sort(), ['CREATED', 'UPDATED'])
    assert.ok(historyRows.every(row => /^[a-f0-9]{64}$/.test(row.snapshotHash)))
  })

  await check('Platform creates knowledge and update records with real Java persistence', async () => {
    await as(platform, 'ROLE_ADMIN'); await route('/platform/materials')
    await until(() => document.querySelector('[data-testid="resource-table"]'), 'materials table')
    for (const kind of ['KNOWLEDGE', 'UPDATE_LOG']) {
      await click('[data-testid="resource-create"]')
      input('[data-testid="resource-kind"]', kind)
      input('[data-testid="resource-title"]', kind === 'KNOWLEDGE' ? 'HTTP DOM 合成帮助资料' : 'HTTP DOM 合成更新记录')
      input('[data-testid="resource-content"]', '仅新建模拟状态\n<script>synthetic()</script> & 保持纯文字')
      await click('[data-testid="resource-save"]')
      await until(() => document.querySelector('.success-banner')?.textContent.includes('已创建'), kind + ' created')
      const value = await dto('POST', '/support/resources/create')
      assert.equal(value.kind, kind)
      if (kind === 'KNOWLEDGE') knowledge = value; else update = value
    }
  })

  await check('Customer custom manual TXT, plaintext materials, updates entry, and all portrait containers', async () => {
    assert.ok(customManual && knowledge && update, 'Requires resource records from prior UI scenarios')
    await as(customer, 'ROLE_USER')
    await route('/mall/manuals')
    await until(() => document.querySelector(`[data-manual-id="${customManual.id}"]`), 'custom manual visible in catalog')
    portrait('/mall/manuals')
    await click(`[data-manual-id="${customManual.id}"]`)
    await until(() => document.querySelector('[data-testid="manual-download-txt"]'), 'custom manual TXT action')
    portrait('/mall/manual')
    const paragraph = el('[data-testid="manual-text"] p')
    assert.equal(paragraph.textContent, customManual.content)
    assert.equal(paragraph.querySelector('b'), null)
    assert.equal(document.querySelector('[data-testid="manual-download-pdf"]'), null)
    const count = downloads.length
    await click('[data-testid="manual-download-txt"]')
    await until(() => downloads.length > count, 'TXT anchor download')
    const file = await downloads.at(-1).promise
    assert.equal(file.mime, 'text/plain;charset=utf-8')
    assert.equal(file.bytes.toString('utf8'), customManual.title + '\n\n' + customManual.content)
    await route('/mall/materials')
    await until(() => document.querySelector('[data-testid="resource-list"]')?.textContent.includes(knowledge.title), 'knowledge in customer materials')
    portrait('/mall/materials')
    assert.ok(el('[data-testid="resource-list"]').textContent.includes(knowledge.content))
    assert.equal(el('[data-testid="resource-list"]').querySelector('script'), null)
    await clickText('更新记录', el('.help-nav'))
    await until(() => document.querySelector('[data-testid="update-timeline"]')?.textContent.includes(update.title), 'update in customer timeline')
    portrait('/mall/updates')
    assert.equal(h.router.currentRoute.value.path, '/mall/updates')
    assert.ok(el('[data-testid="update-timeline"]').textContent.includes(update.content))
    assert.equal(el('[data-testid="update-timeline"]').querySelector('script'), null)
  })

  await check('Platform archives custom resource, retains history, and removes customer visibility', async () => {
    assert.ok(customManual, 'Requires custom manual')
    await as(platform, 'ROLE_ADMIN'); await route('/platform/materials')
    await until(() => document.querySelector(`[data-resource-id="${customManual.id}"]`), 'custom resource row')
    await click(`[data-resource-id="${customManual.id}"]`)
    await click('[data-testid="resource-archive"]')
    await until(() => document.querySelector('.success-banner')?.textContent.includes('已归档'), 'archive success')
    const archived = await dto('POST', '/support/resources/archive')
    assert.equal(archived.status, 'ARCHIVED'); assert.equal(archived.version, 3)
    await until(() => document.querySelectorAll('[data-testid="resource-history"] li').length === 3, 'archive history retained')
    assert.ok(el('[data-testid="resource-editor"] fieldset').disabled)
    assert.equal(document.querySelector('[data-testid="resource-save"]'), null)
    const rows = ok(await platform.client.get('/support/resources/history', { params: { id: customManual.id } }))
    assert.deepEqual(rows.map(row => row.action).sort(), ['ARCHIVED', 'CREATED', 'UPDATED'])
    await as(customer, 'ROLE_USER'); await route('/mall/manuals')
    await until(() => document.querySelector('[data-manual-id="1"]'), 'customer catalog after archive')
    assert.equal(document.querySelector(`[data-manual-id="${customManual.id}"]`), null)
    assert.equal((await customer.client.get('/support/manual', { params: { id: customManual.id } })).code, '404')
  })

  await check('Platform AI model/welcome/intent saves through UI while NOT_CONNECTED and external calls stay false', async () => {
    await as(platform, 'ROLE_ADMIN'); await route('/platform/aiConfiguration')
    await until(() => document.querySelector('[data-testid="ai-model"]'), 'AI configuration form')
    const before = await dto('GET', '/support/platform/ai')
    input('[data-testid="ai-model"]', 'synthetic-ui-model-v7')
    input('[data-testid="ai-welcome"]', 'HTTP DOM 合成欢迎语 <b>按纯文字保存</b>')
    if (!el('[data-testid="ai-enabled"]').checked) await click('[data-testid="ai-enabled"]')
    await click('[data-testid="ai-save"]')
    await until(() => document.querySelector('.success-banner')?.textContent.includes('AI 配置已保存'), 'AI saved notice')
    const saved = await dto('POST', '/support/platform/ai')
    assert.equal(saved.model, 'synthetic-ui-model-v7'); assert.equal(saved.enabled, true)
    assert.equal(saved.welcomeMsg, 'HTTP DOM 合成欢迎语 <b>按纯文字保存</b>')
    assert.equal(saved.version, before.version + 1)
    assert.equal(saved.status, 'NOT_CONNECTED'); assert.equal(saved.externalCallsEnabled, false)
    assert.equal(el('[data-testid="ai-status"]').textContent.trim(), 'NOT_CONNECTED')
    await click('[data-testid="settings-refresh"]')
    await until(() => document.querySelector('[data-testid="ai-model"]')?.value === saved.model, 'AI persistence after refresh')
    assert.deepEqual(ok(await platform.client.get('/support/platform/ai')), saved)
  })

  await check('Bank and WeChat metadata save through UI while NOT_CONNECTED and real charges stay false', async () => {
    await as(platform, 'ROLE_ADMIN')
    for (const [screen, id, name] of [['payBank', 'BANKCARD', '银行卡合成展示'], ['payWechat', 'WECHAT', '微信合成展示']]) {
      await route('/platform/' + screen)
      await until(() => document.querySelector(`[data-testid="payment-label-${id}"]`), id + ' payment form')
      const before = (await dto('GET', '/support/platform/payments')).find(row => row.id === id)
      input(`[data-testid="payment-label-${id}"]`, name)
      if (!el(`[data-testid="payment-enabled-${id}"]`).checked) await click(`[data-testid="payment-enabled-${id}"]`)
      await click(`[data-testid="payment-save-${id}"]`)
      await until(() => document.querySelector('.success-banner')?.textContent.includes('展示配置已保存'), id + ' saved notice')
      const saved = await dto('POST', '/support/platform/payments')
      assert.equal(saved.id, id); assert.equal(saved.label, name); assert.equal(saved.displayEnabled, true)
      assert.equal(saved.version, before.version + 1)
      assert.equal(saved.status, 'NOT_CONNECTED'); assert.equal(saved.chargesEnabled, false)
      assert.equal(el(`[data-testid="payment-status-${id}"]`).textContent.trim(), 'NOT_CONNECTED')
      assert.deepEqual(ok(await platform.client.get('/support/platform/payments')).find(row => row.id === id), saved)
    }
  })

  await check('Customer profile reads and saves own synthetic display fields through actual UI and Java', async () => {
    await as(customer, 'ROLE_USER'); await route('/mall/profile')
    await until(() => document.querySelector('.profile-editor input[placeholder="请输入昵称"]')?.value, 'loaded customer profile')
    portrait('/mall/profile')
    const before = await dto('GET', '/support/profile')
    assert.ok(el('.profile-editor input[disabled]').disabled)
    input('.profile-editor input[placeholder="请输入昵称"]', 'HTTP DOM 合成顾客')
    input('.profile-editor input[placeholder="demo@example.invalid"]', 'resource-ui@example.invalid')
    input('.profile-editor input[placeholder="00000000000"]', '')
    await clickText('保存服务端资料', el('.profile-editor'))
    await until(() => h.router.currentRoute.value.path !== '/mall/profile', 'profile save navigation')
    const saved = await dto('POST', '/support/profile')
    assert.equal(saved.nickname, 'HTTP DOM 合成顾客'); assert.equal(saved.email, 'resource-ui@example.invalid')
    assert.equal(saved.uid, before.uid); assert.equal(saved.loginPhone, before.loginPhone)
    assert.equal(saved.version, before.version + 1)
    assert.deepEqual(ok(await customer.client.get('/support/profile')), saved)
    await route('/mall/profile')
    await until(() => document.querySelector('.profile-editor input[placeholder="请输入昵称"]')?.value === saved.nickname, 'profile persistence after revisit')
    assert.equal(el('.profile-editor input[placeholder="demo@example.invalid"]').value, saved.email)
    portrait('/mall/profile')
  })
} finally {
  app.unmount(); dom.window.close(); globalThis.fetch = nativeFetch
}
console.log(JSON.stringify({ scope: 'Actual compiled Vue DOM handlers and native fetch to real Java HTTP fixture; no browser pixel/layout claim; no external model calls or payment providers', results, totalHttpRequests: trace.length, capturedDownloads: downloads.map(({ name }) => name) }, null, 2))
if (results.some(row => row.status === 'FAIL')) process.exitCode = 1
