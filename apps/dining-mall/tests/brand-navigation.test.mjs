// Actual Vue + Element Plus DOM handlers and checked-in assets, not browser-pixel QA.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { JSDOM } from 'jsdom'

const dom = new JSDOM(fs.readFileSync('index.html', 'utf8'), {
  url: 'https://mall-test.invalid/#/mall/home', pretendToBeVisual: true,
})
for (const key of ['window', 'document', 'location', 'history', 'localStorage', 'sessionStorage', 'Node', 'Document', 'ShadowRoot', 'Element', 'HTMLElement', 'SVGElement', 'HTMLInputElement', 'HTMLTextAreaElement', 'HTMLFormElement', 'Event', 'CustomEvent', 'MouseEvent', 'KeyboardEvent', 'MutationObserver', 'DOMParser', 'FileReader', 'File', 'getComputedStyle']) {
  Object.defineProperty(globalThis, key, { value: key === 'getComputedStyle' ? dom.window[key].bind(dom.window) : dom.window[key], configurable: true })
}
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true })
globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window)
globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} }
dom.window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} })
Element.prototype.scrollTo = function () {}
const originalFetch = globalThis.fetch
const networkCalls = []
globalThis.fetch = async (...args) => { networkCalls.push(String(args[0])); throw Error('Unexpected external fetch in local UI QA') }
dom.window.XMLHttpRequest.prototype.open = function (method, url) { networkCalls.push(String(url)); throw Error('Unexpected external XHR in local UI QA') }

const h = await import('../.test-build/harness.mjs')
const api = h.request
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const ok = response => { assert.equal(response.code, '200', response.msg); return response.data }
async function until(predicate, label) {
  const deadline = Date.now() + 5000
  do { await h.nextTick(); if (predicate()) return; await delay(20) } while (Date.now() < deadline)
  assert.fail(label + '\nDOM: ' + document.body.textContent.slice(-2200))
}
async function route(path) { await h.router.push(path); await delay(70); await h.nextTick() }
async function login(role) {
  await route('/login')
  const actor = ok(await api.post('/web/login', { username: '111', password: '111', role }))
  sessionStorage.setItem('account', JSON.stringify(actor))
}
function appAsset(source) {
  if (source.startsWith('data:')) {
    const comma = source.indexOf(',')
    return source.slice(0, comma).includes(';base64') ? Buffer.from(source.slice(comma + 1), 'base64') : Buffer.from(decodeURIComponent(source.slice(comma + 1)))
  }
  return fs.readFileSync('public' + new URL(source, location.href).pathname)
}
function assertBrandImage(selector, expected) {
  const image = document.querySelector(selector)
  assert.ok(image, 'missing app brand image ' + selector)
  // Vite minimizes inline SVG whitespace and quotes; compare parsed content instead of its encoding.
  function svgStructure(bytes) {
    const xml = new DOMParser().parseFromString(bytes.toString(), 'image/svg+xml')
    assert.equal(xml.querySelector('parsererror'), null, selector + ': valid SVG')
    function element(node) {
      return { tag: node.tagName, attrs: [...node.attributes].map(attr => [attr.name, attr.value]).sort(), text: [...node.childNodes].filter(child => child.nodeType === 3).map(child => child.textContent.trim()).filter(Boolean).join(''), children: [...node.children].map(element) }
    }
    return element(xml.documentElement)
  }
  assert.deepEqual(svgStructure(appAsset(image.getAttribute('src'))), svgStructure(fs.readFileSync(expected)), selector + ': must use the approved dining brand asset')
}
function group(testid, label) {
  const matches = document.querySelectorAll('.admin-sidebar [data-testid="' + testid + '"]')
  assert.equal(matches.length, 1, 'exactly one real sidebar group: ' + label)
  const element = matches[0]
  assert.ok(element.classList.contains('el-sub-menu'), label + ': must be an actual Element Plus submenu')
  assert.equal(element.querySelector(':scope > .el-sub-menu__title').textContent.trim(), label)
  return element
}
async function toggleGroup(element, expanded, label) {
  element.querySelector(':scope > .el-sub-menu__title').click()
  await until(() => element.classList.contains('is-opened') === expanded, label + ': open state must change after title click')
  const items = element.querySelector(':scope > ul')
  assert.ok(items, label + ': submenu list must exist')
  await until(() => (getComputedStyle(items).display !== 'none') === expanded, label + ': submenu display must follow expanded state')
}
async function clickMenu(label, target, root) {
  const item = [...root.querySelectorAll('.el-menu-item')].find(element => element.textContent.trim() === label)
  assert.ok(item, 'missing actual menu item ' + label)
  assert.notEqual(getComputedStyle(item.closest('ul')).display, 'none', 'cannot navigate using a closed submenu')
  item.click()
  await until(() => h.router.currentRoute.value.path === target, label + ': actual click must navigate')
  await until(() => document.querySelector('.admin-content'), 'management content')
}

const app = await h.mount()
try {
  const iconLinks = [...document.querySelectorAll('link[rel*="icon"]')]
  assert.ok(iconLinks.some(link => new URL(link.href).pathname === '/favicon.ico'), 'HTML must explicitly include the ICO fallback')
  for (const link of iconLinks) assert.ok(appAsset(link.getAttribute('href')).length > 0, 'favicon target must exist: ' + link.href)
  assert.deepEqual(fs.readFileSync('public/favicon.svg'), fs.readFileSync('public/brand/dining-app-icon-v5.svg'))
  assert.deepEqual(fs.readFileSync('public/favicon.png'), fs.readFileSync('public/brand/favicon-v5.png'))
  const ico = fs.readFileSync('public/favicon.ico')
  assert.equal(ico.readUInt16LE(0), 0); assert.equal(ico.readUInt16LE(2), 1)
  const iconCount = ico.readUInt16LE(4); assert.ok(iconCount >= 1, 'ICO must include image entries')
  for (let n = 0; n < iconCount; n++) {
    const offset = 6 + n * 16, size = ico.readUInt32LE(offset + 8), start = ico.readUInt32LE(offset + 12)
    assert.ok(size > 0 && start + size <= ico.length, 'ICO entry must point to valid image bytes')
  }
  await until(() => document.querySelector('.mall-app-brand img'), 'customer home brand')
  assertBrandImage('.mall-app-brand img', 'public/brand/dining-logo-v5.svg')
  assert.equal(document.title, '鲜食好店', 'customer title must not repeat the project name')
  await route('/login')
  assertBrandImage('.app-brand-icon', 'public/brand/dining-logo-v5.svg')
  assertBrandImage('.entry-logo', 'public/brand/dining-app-icon-v5.svg')
  assert.equal(document.title, '登录 - 鲜食好店')

  await login('ROLE_ADMIN'); await route('/platform/support')
  assertBrandImage('.admin-header .logo-image', 'public/brand/dining-logo-v5.svg')
  assert.equal(document.querySelector('.admin-header .logo-text').textContent, '鲜食好店 · 平台管理')
  const paymentsBefore = JSON.stringify(ok(await api.get('/support/platform/payments')))
  const aiBefore = JSON.stringify(ok(await api.get('/support/platform/ai')))
  const payments = group('payment-nav-group', '支付接入'), ai = group('ai-nav-group', 'AI客服管理')
  for (const [element, label] of [[payments, '支付接入'], [ai, 'AI客服管理']]) {
    assert.equal(element.classList.contains('is-opened'), false, label + ': initially collapsed')
    await toggleGroup(element, true, label)
    await toggleGroup(element, false, label)
    await toggleGroup(element, true, label)
  }
  await clickMenu('支付接入概览', '/platform/paymentManagement', payments)
  await until(() => document.querySelectorAll('[data-payment-id]').length === 4, 'payment overview must render four channels')
  for (const [label, path, channel] of [['微信支付接入', 'payWechat', 'WECHAT'], ['银行卡支付接入', 'payBank', 'BANKCARD'], ['支付宝支付接入', 'payAlipay', 'ALIPAY']]) {
    await clickMenu(label, '/platform/' + path, payments)
    await until(() => document.querySelector('[data-testid="payment-status-' + channel + '"]'), label + ': channel configuration')
    assert.equal(document.querySelector('.service-settings').dataset.settingsSection, path)
    assert.equal(document.querySelectorAll('[data-payment-id]').length, 1, label + ': only selected payment channel')
    assert.equal(document.querySelector('[data-testid="payment-status-' + channel + '"]').textContent, 'NOT_CONNECTED')
  }
  await clickMenu('AI配置与连接', '/platform/aiConfiguration', ai)
  await until(() => document.querySelector('[data-testid="ai-status"]'), 'AI configuration must render')
  assert.equal(document.querySelector('[data-testid="ai-status"]').textContent, 'NOT_CONNECTED')
  assert.ok(document.querySelector('[data-testid="ai-model"]'))
  await clickMenu('客服会话与消息', '/platform/aiSessions', ai)
  await until(() => document.querySelector('[data-testid="support-workspace"]'), 'AI sessions must render the actual support workspace directly')
  assert.equal(document.querySelector('[data-testid="support-workspace"]').dataset.role, 'ROLE_ADMIN')
  assert.ok(!document.querySelector('.service-settings'), 'AI sessions should be the conversation workspace')
  await toggleGroup(payments, false, '支付接入')
  await toggleGroup(ai, false, 'AI客服管理')
  assert.equal(JSON.stringify(ok(await api.get('/support/platform/payments'))), paymentsBefore, 'navigation must not modify payment configuration')
  assert.equal(JSON.stringify(ok(await api.get('/support/platform/ai'))), aiBefore, 'navigation must not modify AI configuration')

  await login('ROLE_UNIT'); await route('/merchant/support')
  assertBrandImage('.admin-header .logo-image', 'public/brand/dining-logo-v5.svg')
  assert.equal(document.querySelector('.admin-header .logo-text').textContent, '鲜食好店 · 商家端')
  assert.equal(document.querySelectorAll('[data-testid="payment-nav-group"],[data-testid="ai-nav-group"]').length, 0, 'merchant must not see platform groups')
  const adminOnly = ['paymentManagement', 'payWechat', 'payBank', 'payAlipay', 'aiManagement', 'aiConfiguration', 'aiSessions', 'materials', 'admin', 'user', 'tag']
  for (const page of adminOnly) {
    await route('/merchant/' + page)
    assert.equal(h.router.currentRoute.value.path, '/merchant/home', 'merchant forbidden route: ' + page)
    assert.ok(!document.querySelector('.service-settings'), 'merchant must not render platform configuration')
  }
  await route('/platform/aiSessions')
  assert.equal(h.router.currentRoute.value.path, '/merchant/home', 'merchant must not enter platform route family')
  assert.notEqual((await api.get('/support/platform/ai')).code, '200')
  assert.notEqual((await api.get('/support/platform/payments')).code, '200')
  assert.deepEqual(networkCalls, [], 'local navigation must not call external services')
  console.log('PASS actual Vue/Element Plus DOM: unique real payment + AI sidebar groups collapse/expand/collapse; payment overview + 3 provider pages; AI configuration + direct session workspace; merchant route/API denial; customer/login/admin SVG asset structures; valid root favicon fallbacks; deduplicated title; unchanged payment/AI config; zero external fetch/XHR')
  console.log('Scope: mounted application DOM and actual click handlers plus asset-file validation. No browser pixels, favicon cache behavior, real payment, or external AI claimed.')
} finally {
  app.unmount(); dom.window.close(); globalThis.fetch = originalFetch
}
