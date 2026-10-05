// Real Vue/JSDOM route and control regression coverage for the responsive customer shell.
// Run after: node tests/build-ui-harness.mjs
// CSS geometry, breakpoints, visibility and touch hit areas require browser checks.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!doctype html><html><body><div id="app"></div></body></html>', {
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
// Resolve real checked-in manual assets without external HTTP or fabricated asset responses.
const nativeFetch = globalThis.fetch
globalThis.fetch = async (input, init) => {
  if (typeof input === 'string' && /^\/manual-assets\/[A-Za-z0-9._-]+$/.test(input)) {
    return new Response(await fs.readFile(new URL('../public' + input, import.meta.url)))
  }
  return nativeFetch(input, init)
}
const h = await import('../.test-build/harness.mjs')
const api = h.request
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const success = response => { assert.equal(response.code, '200', response.msg); return response.data }
async function until(predicate, label) {
  const deadline = Date.now() + 5000
  do { await h.nextTick(); if (predicate()) return; await delay(15) } while (Date.now() < deadline)
  assert.fail(label + '\nDOM: ' + document.body.textContent.slice(-1600))
}
async function login(role = 'ROLE_USER') {
  const actor = success(await api.post('/web/login', { username: '111', password: '111', role }))
  sessionStorage.setItem('account', JSON.stringify(actor))
  return actor
}
async function route(path) { await h.router.push(path); await delay(35); await h.nextTick() }
function customerShell(label) {
  assert.ok(document.querySelector('.native-stage .native-device .native-app'), label + ': missing customer shell')
  assert.equal(document.querySelectorAll('.native-app').length, 1, label + ': duplicate customer application')
  assert.ok(!document.querySelector('.admin-layout,.admin-container,.admin-sidebar,.back-container'), label + ': entered management UI')
  assert.ok(h.router.currentRoute.value.path.startsWith('/mall/'), label + ': escaped customer route family')
}
async function click(text, root = document) {
  const button = [...root.querySelectorAll('button')].find(element => element.textContent.trim() === text || [...element.children].some(child => child.tagName === 'SPAN' && child.textContent.trim() === text))
  assert.ok(button, 'missing button: ' + text)
  assert.ok(!button.disabled, 'disabled button: ' + text)
  button.click(); await delay(40); await h.nextTick()
}
async function render(path, selector) {
  await route(path)
  await until(() => document.querySelector(selector), path + ': missing expected view ' + selector)
  customerShell(path)
  assert.equal(h.router.currentRoute.value.path, path.split('?')[0], path + ': unexpected redirect')
}

const app = await h.mount()
let routeCount = 0
try {
  await login()
  // Login normally happens on /login, so remount the customer component with that actor.
  await route('/login')
  success(await api.post('/cart', { goodsId: 700001, num: 1, unitId: 1 }))
  const session = success(await api.post('/support/open-session', { unitId: '1', clientKey: 'responsive-session' }))
  const ticket = success(await api.post('/support/create-ticket', { unitId: '1', title: 'Responsive route fixture', content: 'Synthetic local QA ticket', priority: 'MEDIUM', attachmentIds: [], clientKey: 'responsive-ticket' }))
  const refund = success(await api.post('/after-sales', { orderId: 3, requestedMinor: 100, reason: 'Synthetic route QA case', clientKey: 'responsive-refund' }))
  const views = [
    ['/mall/home', '.food-grid .food-card'],
    ['/mall/category', '.native-list .native-product-row'],
    ['/mall/shops', '.shop-summary'],
    ['/mall/shop?id=1', '.shop-summary'],
    ['/mall/detail?id=700001', '.detail-info'],
    ['/mall/cart', '.cart-row'],
    ['/mall/checkout', '.payment-option'],
    ['/mall/orders', '.order-card'],
    ['/mall/orderDetail?id=3', '.purchase-feedback'],
    ['/mall/bill', '.credit-wallet'],
    ['/mall/deferredOrder?id=missing', '.credit-customer'],
    ['/mall/orderService?id=3', '[data-testid="purchase-feedback"]'],
    ['/mall/afterSales', '.after-sales-case'],
    ['/mall/afterSale?id=' + refund.id, '.after-sales-state'],
    ['/mall/support', '.support-entry-grid'],
    ['/mall/supportChat?id=' + session.id, '[data-testid="customer-support-composer"]'],
    ['/mall/supportTickets', '.support-ticket-card'],
    ['/mall/supportNew?unitId=1&orderId=3', '[data-testid="customer-ticket-form"]'],
    ['/mall/supportTicket?id=' + ticket.id, '.support-ticket-state'],
    ['/mall/manuals', '[data-testid="manual-catalog"]'],
    ['/mall/manual?id=1', '[data-testid="manual-page-image"]'],
    ['/mall/materials', '[data-testid="resource-list"]'],
    ['/mall/updates', '[data-testid="update-timeline"]'],
    ['/mall/address', '.address-actions'],
    ['/mall/profile', '.profile-editor'],
    ['/mall/mine', '.service-grid'],
    ['/mall/notFound', '.native-empty'],
  ]
  for (const [path, selector] of views) { await render(path, selector); routeCount++ }

  // Customer actions continue through the same application, including modal editors.
  await render('/mall/home', '.native-tabbar')
  for (const [label, target] of [['分类', 'category'], ['购物车', 'cart'], ['我的', 'mine'], ['首页', 'home']]) {
    await click(label, document.querySelector('.native-tabbar'))
    assert.equal(h.router.currentRoute.value.path, '/mall/' + target)
    customerShell('tab ' + label)
  }
  await render('/mall/detail?id=700001', '.detail-info')
  const selector = [...document.querySelectorAll('.native-card.native-line')].find(element => element.textContent.includes('标准份'))
  assert.ok(selector); selector.click(); await h.nextTick()
  assert.ok(document.querySelector('.native-device .native-overlay .native-sheet'), 'product options must stay in customer device')
  document.querySelector('.native-overlay .sheet-close').click(); await h.nextTick()
  await click('立即下单')
  await until(() => h.router.currentRoute.value.path === '/mall/checkout', 'buy action must open customer checkout')
  customerShell('buy')
  document.querySelector('.address-summary').click(); await delay(40)
  assert.equal(h.router.currentRoute.value.path, '/mall/address'); assert.equal(h.router.currentRoute.value.query.select, '1')
  await click('选择此地址')
  assert.equal(h.router.currentRoute.value.path, '/mall/checkout')
  await render('/mall/orderService?id=3', '[data-testid="purchase-feedback"]')
  await click('去评价')
  assert.ok(document.querySelector('.native-device [data-testid="review-editor"] textarea'))
  document.querySelector('[data-testid="review-editor"] .sheet-close').click(); await h.nextTick()
  await click('申请售后 / 退款')
  assert.ok(document.querySelector('.native-device [data-testid="after-sales-editor"] [aria-label="售后申请金额"]'))
  document.querySelector('[data-testid="after-sales-editor"] .sheet-close').click(); await h.nextTick()
  await render('/mall/manuals', '.manual-card')
  document.querySelector('.manual-card').click()
  await until(() => document.querySelector('[data-testid="manual-page-image"]'), 'manual card must open actual local manual assets')
  customerShell('manual card')
  await click('返回手册目录')
  assert.equal(h.router.currentRoute.value.path, '/mall/manuals')

  // Every supported old customer deep link preserves query state and maps to customer UI.
  const legacy = {
    home: 'home', goods: 'category', goodsDetail: 'detail', search: 'category', unit: 'shop',
    collect: 'category', person: 'profile', password: 'profile', service: 'support', chat: 'support',
    message: 'supportTickets', manual: 'manuals', updateLog: 'updates', updatelog: 'updates',
    address: 'address', cart: 'cart', orders: 'orders',
  }
  for (const [old, current] of Object.entries(legacy)) {
    await route('/front/' + old + '?id=1&keyword=route-check&trace=keep')
    assert.equal(h.router.currentRoute.value.path, '/mall/' + current, 'legacy ' + old)
    assert.equal(h.router.currentRoute.value.query.trace, 'keep', 'legacy query lost: ' + old)
    assert.equal(h.router.currentRoute.value.query.keyword, 'route-check')
    if (old === 'collect') assert.equal(String(h.router.currentRoute.value.query.favorites), '1')
    customerShell('legacy ' + old); routeCount++
  }
  await route('/front'); assert.equal(h.router.currentRoute.value.path, '/mall/home'); customerShell('/front')
  await route('/front/unknown'); assert.equal(h.router.currentRoute.value.path, '/mall/notFound'); customerShell('legacy unknown')
  await route('/mall/unknown'); assert.equal(h.router.currentRoute.value.path, '/mall/home'); customerShell('unknown customer view')
  await route('/404'); assert.equal(h.router.currentRoute.value.path, '/mall/notFound'); customerShell('/404')

  // Anonymous deep links return to the same customer destination after real form login.
  await route('/login'); sessionStorage.removeItem('account')
  const target = '/mall/supportNew?unitId=1&orderId=3'
  await route(target)
  assert.equal(h.router.currentRoute.value.path, '/login')
  assert.equal(h.router.currentRoute.value.query.returnTo, target)
  const form = document.querySelector('.entry-content form')
  assert.ok(form && form.querySelector('[aria-label="体验账号"]'), 'missing customer entry form')
  assert.ok(!document.querySelector('.admin-sidebar'), 'entry route must not render management UI')
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await until(() => h.router.currentRoute.value.path === '/mall/supportNew', 'login must restore protected customer deep link')
  await until(() => document.querySelector('[data-testid="customer-ticket-form"]'), 'restored ticket form')
  assert.equal(document.querySelector('[aria-label="关联订单"]').value, '3')
  customerShell('login returnTo')
  for (const path of ['/merchant/home', '/platform/home', '/back/goods']) {
    await route(path); assert.equal(h.router.currentRoute.value.path, '/login', 'customer forbidden management route ' + path)
    assert.ok(!document.querySelector('.admin-sidebar'))
  }
  // A management identity cannot silently gain access to protected customer content.
  for (const role of ['ROLE_UNIT', 'ROLE_ADMIN']) {
    await login(role)
    for (const path of ['/mall/checkout', '/mall/orderService?id=3', '/mall/afterSales', '/mall/supportTickets', '/mall/manual?id=1', '/mall/profile']) {
      await route(path); assert.equal(h.router.currentRoute.value.path, '/login', role + ' accessed ' + path)
      assert.equal(h.router.currentRoute.value.query.returnTo, path)
    }
  }
  console.log(`PASS ${routeCount} real customer/legacy route renders, tab navigation, detail→checkout→address, review/refund editors, real manual assets, login returnTo, and customer/management route isolation`)
  console.log('Scope: Vue DOM structure and handlers only. JSDOM does not verify 9:16, breakpoint geometry, visible controls, touch targets, or landscape layout.')
} finally {
  app.unmount(); dom.window.close(); globalThis.fetch = nativeFetch
}
