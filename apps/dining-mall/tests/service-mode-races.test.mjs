// Session-boundary regressions use synthetic fetch responses and fresh memory only.
import assert from 'node:assert/strict'

const memory = new Map()
globalThis.sessionStorage = { getItem: key => memory.get(key) ?? null, setItem: (key, value) => memory.set(key, value), removeItem: key => memory.delete(key) }
const actorA = { id: '101', uid: 'A', name: 'Actor A', role: 'ROLE_USER' }
const actorB = { id: '202', uid: 'B', name: 'Actor B', role: 'ROLE_ADMIN' }
const profileA = { id: '101', uid: 'A', username: 'Account A', nickname: 'A display name', avatarUrl: '/avatar.svg', email: 'a@example.invalid', contactPhone: '111', version: 2 }
const envelope = data => ({ data, meta: { environment: 'SIMULATION' } })
const success = data => ({ ok: true, status: 200, json: async () => envelope(data) })
let actor = actorA, hold = null
const calls = []
globalThis.fetch = async (url, options) => {
  const path = new URL(url).pathname
  calls.push({ path, method: options.method, token: options.headers.Authorization })
  if (path.endsWith('/support/context')) return success({ actor, shops: [], orders: [] })
  if (hold?.path === path && hold.method === options.method) {
    const pending = hold
    hold = null
    return { ok: true, status: 200, json: () => pending.promise }
  }
  if (path.endsWith('/support/profile')) return success(profileA)
  if (path.endsWith('/support/create-ticket')) return success({ id: 'T1', detailRequired: true })
  if (path.endsWith('/support/ticket')) return success({ id: 'T1', replies: [], history: [] })
  throw Error('Unexpected synthetic route: ' + path)
}
const service = await import('../src/utils/service-mode.js')
const account = () => memory.get('account')
const tick = () => new Promise(resolve => setImmediate(resolve))
async function attach(next) {
  actor = next
  await service.attachServiceSession({ baseUrl: 'http://localhost:18080', token: 'synthetic-' + next.uid })
}
function defer(path, method) {
  let resolve
  const promise = new Promise(done => { resolve = done })
  hold = { path: '/api/v1' + path, method, promise }
  return data => resolve(envelope(data))
}
async function test(name, run) { await run(); console.log('PASS', name) }

await test('late actor A profile save cannot change newly attached actor B account', async () => {
  await attach(actorA)
  const finish = defer('/support/profile', 'POST')
  const save = service.backendRequest('post', '/user', { id: '101', version: 1, nickname: profileA.nickname })
  await tick()
  await attach(actorB)
  const before = account()
  finish(profileA)
  assert.equal((await save).code, '409')
  assert.equal(account(), before)
  assert.equal(JSON.parse(account()).id, '202')
  assert.ok(!account().includes('a@example.invalid'))
})

await test('late profile save after demo switch returns conflict without restoring account or throwing', async () => {
  await attach(actorA)
  const finish = defer('/support/profile', 'POST')
  const save = service.backendRequest('post', '/user', { id: '101', version: 1, nickname: profileA.nickname })
  await tick()
  service.useDemoMode()
  finish(profileA)
  assert.equal((await save).code, '409')
  assert.equal(account(), undefined)
})

await test('late profile read after demo switch does not dereference cleared context', async () => {
  await attach(actorA)
  const finish = defer('/support/profile', 'GET')
  const read = service.backendRequest('get', '/web/userInfo')
  await tick()
  service.useDemoMode()
  finish(profileA)
  assert.equal((await read).code, '409')
  assert.equal(account(), undefined)
})

await test('late compact command result starts no detail request in a newer session', async () => {
  await attach(actorA)
  const finish = defer('/support/create-ticket', 'POST')
  const save = service.backendRequest('post', '/support/create-ticket', { unitId: '1', title: 'Synthetic', content: 'Synthetic', priority: 'LOW' })
  await tick()
  await attach(actorB)
  const before = account(), requestCount = calls.length
  finish({ id: 'T1', detailRequired: true })
  assert.equal((await save).code, '409')
  assert.equal(calls.length, requestCount)
  assert.equal(account(), before)
})

await test('late detail response cannot finish a command in a newer session', async () => {
  await attach(actorA)
  const finish = defer('/support/ticket', 'GET')
  const save = service.backendRequest('post', '/support/create-ticket', { unitId: '1', title: 'Synthetic', content: 'Synthetic', priority: 'LOW' })
  await tick()
  assert.equal(calls.at(-1).token, 'Bearer synthetic-A')
  await attach(actorB)
  const before = account()
  finish({ id: 'T1', replies: [], history: [] })
  assert.equal((await save).code, '409')
  assert.equal(account(), before)
})

await test('unchanged actor profile save still maps identity and persists the returned version', async () => {
  await attach(actorA)
  const save = await service.backendRequest('post', '/user', { id: '101', version: 1, nickname: profileA.nickname })
  assert.equal(save.code, '200')
  assert.equal(save.data.id, '101')
  assert.equal(save.data.uid, 'A')
  assert.equal(save.data.version, 2)
  assert.equal(JSON.parse(account()).email, 'a@example.invalid')
})
