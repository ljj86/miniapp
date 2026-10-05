import {requestTestGlobals} from './request-test-globals.mjs'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import * as credit from '../src/utils/credit-service.js'
import * as feedback from '../src/utils/feedback-service.js'
import * as support from '../src/utils/support-service.js'
const source=fs.readFileSync(new URL('../src/utils/request.js',import.meta.url),'utf8').replace(/^import.*$/mg,'').replace(/export const /g,'const ').replace('export default','globalThis.api=')
const seed=JSON.parse(fs.readFileSync(new URL('../src/utils/seed.json',import.meta.url)))
const shared=new Map(),session=new Map(),storage=map=>({getItem:key=>map.get(key)||null,setItem:(key,value)=>map.set(key,value),removeItem:key=>map.delete(key)})
let hold=false;const pending=[]
const ctx={...requestTestGlobals,...credit,...feedback,...support,seed,localStorage:storage(shared),sessionStorage:storage(session),sanitizeHTML:s=>s,location:{},navigator:{locks:{request:(_,fn)=>hold?new Promise(resolve=>pending.push(async()=>resolve(await fn()))):fn()}}}
vm.createContext(ctx);vm.runInContext(source,ctx);const api=ctx.api
const ok=r=>{assert.equal(r.code,'200',r.msg);return r.data}
const set=role=>session.set('account',JSON.stringify({id:1,role}))
set('ROLE_USER');const s=ok(await api.post('/support/open-session',{unitId:'1',clientKey:'open-request-stable-key'}));const before=shared.get('mall-ui-data-v2')
hold=true;const send=api.post('/support/send-message',{sessionId:s.id,text:'发起时是顾客',clientKey:'queued-request-stable-key'});assert.equal(pending.length,1);set('ROLE_UNIT');await pending.shift()();assert.equal((await send).code,'400');assert.equal(shared.get('mall-ui-data-v2'),before)
hold=false;const m=ok(await api.post('/support/send-message',{sessionId:s.id,text:'现在明确以本店回复',clientKey:'merchant-request-stable-key'}));assert.equal(m.senderRole,'ROLE_UNIT')
hold=true;const staleRead=api.get('/support/session',{params:{id:s.id}});session.delete('account');await pending.shift()();assert.equal((await staleRead).code,'400')
hold=false;set('ROLE_USER');const read=ok(await api.get('/support/session',{params:{id:s.id}}));assert.equal(read.messages.length,1);assert.equal(read.messages[0].senderRole,'ROLE_UNIT');assert.equal(read.session.unread,1)
const db=JSON.parse(shared.get('mall-ui-data-v2'));assert.equal(db.credit,undefined);assert.deepEqual(db.orders,seed.orders)
console.log('PASS actual adapter: queued operations reject identity switches; no stale customer text posted as merchant; queued reads reject logout; valid later operation works; ledger/orders unchanged')
