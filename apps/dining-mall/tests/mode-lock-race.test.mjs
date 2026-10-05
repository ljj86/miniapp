// Isolated request-adapter regression tests; no browser/server or shared storage.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { requestTestGlobals } from './request-test-globals.mjs'

const source = fs.readFileSync(new URL('../src/utils/request.js', import.meta.url), 'utf8')
  .replace(/^import.*$/mg, '').replace(/export const /g, 'const ')
  .replace('export default', 'globalThis.api=')
const seed = JSON.parse(fs.readFileSync(new URL('../src/utils/seed.json', import.meta.url)))
const key = 'mall-ui-data-v2'
const storage = map => ({ getItem: key => map.get(key) ?? null, setItem: (key, value) => map.set(key, value), removeItem: key => map.delete(key) })

function harness() {
  const local = new Map([[key, JSON.stringify(seed)]]), session = new Map(), pending = [], backendCalls = []
  const connection = { mode: 'demo', revision: 0 }
  const setActor = value => session.set('account', JSON.stringify(value))
  setActor({ id: 1, role: 'ROLE_USER' })
  const context = {
    ...requestTestGlobals, seed, serviceConnection: connection,
    isBackendMode: () => connection.mode === 'backend',
    backendRequest: async (...args) => { backendCalls.push(args); return { code: '200', data: { source: 'synthetic-backend' } } },
    localStorage: storage(local), sessionStorage: storage(session), sanitizeHTML: value => value, location: {},
    navigator: { locks: { request: (_, execute) => new Promise((resolve, reject) => pending.push(async () => { try { resolve(await execute()) } catch (error) { reject(error) } })) } },
  }
  vm.createContext(context)
  vm.runInContext(source, context)
  return {
    api: context.api, local, pending, backendCalls, connection, setActor,
    switchMode(mode, actor = { id: '1', role: 'ROLE_USER', backendIdentity: mode === 'backend' }) {
      connection.mode = mode
      connection.revision++
      setActor(actor)
    },
    async release() { assert.equal(pending.length, 1); await pending.shift()() },
  }
}

async function test(name, run) { await run(); console.log('PASS', name) }

await test('queued demo save rejects backend switch even when role and string ID match', async () => {
  const h = harness(), before = h.local.get(key)
  const save = h.api.post('/user', { id: 1, nickname: 'Must not be saved' })
  h.switchMode('backend')
  await h.release()
  assert.equal((await save).code, '400')
  assert.equal(h.local.get(key), before)
  assert.equal(h.backendCalls.length, 0)
})

await test('queued demo delete rejects a backend-to-demo round trip', async () => {
  const h = harness(), before = h.local.get(key)
  const remove = h.api.delete('/address/1')
  h.switchMode('backend')
  h.switchMode('demo', { id: 1, role: 'ROLE_USER' })
  await h.release()
  assert.equal((await remove).code, '400')
  assert.equal(h.local.get(key), before)
  assert.equal(h.backendCalls.length, 0)
})

await test('queued read with local persistence also rejects a mode switch', async () => {
  const h = harness(), before = h.local.get(key)
  const read = h.api.get('/credit/overview')
  h.switchMode('backend')
  await h.release()
  assert.equal((await read).code, '400')
  assert.equal(h.local.get(key), before)
  assert.equal(h.backendCalls.length, 0)
})

await test('existing identity-switch guard still rejects another demo actor', async () => {
  const h = harness(), before = h.local.get(key)
  const save = h.api.post('/user', { id: 1, nickname: 'Wrong actor' })
  h.setActor({ id: 2, role: 'ROLE_USER' })
  await h.release()
  assert.equal((await save).code, '400')
  assert.equal(h.local.get(key), before)
})

await test('unchanged demo session executes its queued save after harmless display changes', async () => {
  const h = harness()
  const save = h.api.post('/user', { id: 1, nickname: 'Allowed local save' })
  h.setActor({ id: 1, role: 'ROLE_USER', nickname: 'Updated display name' })
  await h.release()
  assert.equal((await save).code, '200')
  assert.equal(JSON.parse(h.local.get(key)).user.find(row => row.id === 1).nickname, 'Allowed local save')
  assert.equal(h.backendCalls.length, 0)
})

await test('new backend requests bypass the demo lock and leave local data untouched', async () => {
  const h = harness(), before = h.local.get(key)
  h.switchMode('backend')
  const result = await h.api.get('/support/overview')
  assert.equal(result.code, '200')
  assert.equal(result.data.source, 'synthetic-backend')
  assert.equal(h.pending.length, 0)
  assert.equal(h.backendCalls.length, 1)
  assert.equal(h.local.get(key), before)
})
