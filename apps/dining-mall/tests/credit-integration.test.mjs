import {requestTestGlobals} from './request-test-globals.mjs'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import * as credit from '../src/utils/credit-service.js'
import * as feedback from '../src/utils/feedback-service.js'
const source=fs.readFileSync(new URL('../src/utils/request.js',import.meta.url),'utf8').replace(/^import.*$/mg,'').replace(/export const /g,'const ').replace('export default','globalThis.api=')
const seed=JSON.parse(fs.readFileSync(new URL('../src/utils/seed.json',import.meta.url)))
const shared=new Map(),storage=map=>({getItem:key=>map.get(key)||null,setItem:(key,value)=>map.set(key,value),removeItem:key=>map.delete(key)})
function client(){const session=new Map(),ctx={...requestTestGlobals,...credit,...feedback,seed,localStorage:storage(shared),sessionStorage:storage(session),sanitizeHTML:s=>s,location:{}};vm.createContext(ctx);vm.runInContext(source,ctx);return {api:ctx.api,session}}
const customer=client(),merchant=client(),platform=client(),otherMerchant=client()
async function login(c,role){const r=await c.api.post('/web/login',{username:'111',password:'111',role});assert.equal(r.code,'200');c.session.set('account',JSON.stringify(r.data));}
const api=customer.api
const get=async c=>(await c.api.get('/credit/overview')).data
function success(r,label){assert.equal(r.code,'200',label+': '+r.msg);return r.data}
await login(customer,'ROLE_USER');await login(merchant,'ROLE_UNIT');await login(platform,'ROLE_ADMIN');otherMerchant.session.set('account',JSON.stringify({id:2,role:'ROLE_UNIT'}))
let c=await get(customer);assert.equal(c.account.availableMinor,10000)
await api.post('/cart',{goodsId:700001,num:1,unitId:1})
let cart=(await api.get('/cart')).data.find(c=>c.goodsId===700001)
let placed=success(await api.post('/orders/fromCart/1',[{id:cart.id,goodsId:700001,num:1,paymentMode:'later',remark:'少辣'}]),'customer dining request')
assert.equal(placed[0].status,'待商家发布');assert.equal((await get(customer)).account.reservedMinor,0)
let m=await get(merchant);assert.equal(m.requests.length,1);assert.equal(m.account,undefined);assert.equal((await get(otherMerchant)).requests.length,0)
assert.equal((await customer.api.post('/credit/publish',{requestId:m.requests[0].id})).code,'400')
let published=success(await merchant.api.post('/credit/publish',{requestId:m.requests[0].id}),'merchant publish')
c=await get(customer);let order=c.orders.find(o=>o.id===published.id)
assert.equal(c.account.reservedMinor,0)
const confirmedPayload={orderId:order.id,credential:order.confirmationCredential.credential,version:order.version,snapshotHash:order.snapshotHash,agreementAccepted:true}
success(await api.post('/credit/confirm',confirmedPayload),'customer confirm');success(await api.post('/credit/confirm',confirmedPayload),'confirm replay')
c=await get(customer);m=await get(merchant);let p=await get(platform)
assert.equal(c.account.reservedMinor,2000);assert.equal(m.merchantSummary.reservedMinor,2000);assert.equal(p.accounts[0].reservedMinor,2000)
let code=success(await api.post('/credit/fulfillment-code',{orderId:order.id}),'customer code')
assert.equal((await otherMerchant.api.post('/credit/redeem',{orderId:order.id,credential:code.credential})).code,'400')
success(await merchant.api.post('/credit/redeem',{orderId:order.id,credential:code.credential}),'merchant redeem');success(await merchant.api.post('/credit/redeem',{orderId:order.id,credential:code.credential}),'redeem replay')
c=await get(customer);m=await get(merchant);p=await get(platform)
assert.equal(c.account.principalMinor,2000);assert.equal(c.account.reservedMinor,0);assert.equal(m.merchantSummary.principalMinor,2000);assert.equal(p.accounts[0].principalMinor,2000);assert.equal(c.receivables.length,1)
const retail=(await api.get('/orders/front/page',{params:{pageSize:100}})).data.records.find(o=>o.creditOrderId===order.id)
for(const patch of [{price:1},{unitId:2},{userId:2},{creditOrderId:null},{creditRequestId:null}])assert.equal((await platform.api.post('/orders',{...retail,...patch})).code,'400')
assert.equal((await api.get('/orders/'+retail.id)).data.price,20)
assert.equal((await api.get('/orders/cancel/'+retail.id)).code,'400')
let repayment=success(await api.post('/credit/repay',{merchantUid:c.receivables[0].merchantUid,receivableIds:[c.receivables[0].id],amountMinor:800,clientKey:'integration-eight'}),'customer repay')
assert.equal((await get(customer)).account.principalMinor,2000);assert.equal(repayment.status,'PENDING')
const callback={personaId:'701',repaymentId:repayment.id,reference:repayment.reference,merchantUid:repayment.merchantUid,amountMinor:800,currency:'CNY',environment:'SIMULATION',providerEventId:'INTEGRATION-ONE',result:'CONFIRMED'}
assert.equal((await api.post('/credit/simulate-result',callback)).code,'400')
success(await platform.api.post('/credit/simulate-result',callback),'platform simulated success');success(await platform.api.post('/credit/simulate-result',callback),'same event replay');success(await platform.api.post('/credit/simulate-result',{...callback,providerEventId:'INTEGRATION-TWO'}),'same reference new event')
c=await get(customer);m=await get(merchant);p=await get(platform)
assert.equal(c.account.principalMinor,1200);assert.equal(c.account.availableMinor,8800);assert.equal(m.merchantSummary.principalMinor,1200);assert.equal(m.merchantSummary.repaidMinor,800);assert.equal(p.accounts[0].principalMinor,1200);assert.equal(p.invariant,true)
assert.equal((await get(otherMerchant)).receivables.length,0)
// A fresh module reads the same committed business state, while identity remains per tab.
const refreshed=client();refreshed.session.set('account',customer.session.get('account'));assert.equal((await get(refreshed)).account.availableMinor,8800)
console.log('PASS: customer request → merchant publish → customer confirm/reserve → one-time merchant redemption → shared receivable → customer pending repayment → platform result → same ¥12 remaining / ¥88 available across all three roles')
console.log('PASS: replay idempotency, cross-merchant privacy/authorization, immutable financial retail link/amount/ownership, per-tab role isolation, local reload continuity')

// Adversarial business validation: no negative/non-finite prices and no stale publish confirmation.
let product=(await platform.api.get('/goods/700001')).data
for(const bad of [-1,0,Infinity,NaN,0.001]){
 const before=JSON.stringify((await platform.api.get('/goods/700001')).data)
 const rejected=await platform.api.post('/goods',{...product,price:bad})
 assert.equal(rejected.code,'400','invalid price must reject: '+bad)
 assert.equal(JSON.stringify((await platform.api.get('/goods/700001')).data),before)
}
async function publishedRequest(){
 await api.post('/cart',{goodsId:700001,num:1,unitId:1})
 const cart=(await api.get('/cart')).data.find(c=>c.goodsId===700001)
 success(await api.post('/orders/fromCart/1',[{id:cart.id,goodsId:700001,num:1,paymentMode:'later'}]),'new request')
 const req=(await get(merchant)).requests.find(r=>r.status==='WAITING_MERCHANT')
 success(await merchant.api.post('/credit/publish',{requestId:req.id}),'publish new quote')
 return (await get(customer)).orders.find(o=>o.requestId===req.id)
}
for(const change of ['price','status','version','delete']){
 product=(await platform.api.get('/goods/700001')).data
 const o=await publishedRequest()
 const beforeAccount=(await get(customer)).account
 if(change==='delete'){await platform.api.delete('/goods/700001');assert.equal((await platform.api.get('/goods/700001')).code,'400','deleted fixture must not reappear')}
 else {
  const next={...product,...(change==='price'?{price:19}:change==='status'?{status:false}:{info:product.info+' revision'})}
  success(await platform.api.post('/goods',next),'edit product '+change)
 }
 const input={orderId:o.id,credential:o.confirmationCredential.credential,version:o.version,snapshotHash:o.snapshotHash,agreementAccepted:true}
 const rejected=await api.post('/credit/confirm',input)
 assert.equal(rejected.code,'400','confirmation must reject stale '+change)
 const after=(await get(customer))
 assert.equal(after.account.reservedMinor,beforeAccount.reservedMinor)
 assert.equal(after.account.principalMinor,beforeAccount.principalMinor)
 assert.equal(after.orders.find(x=>x.id===o.id).status,'PUBLISHED')
 if(change!=='delete')success(await platform.api.post('/goods',{...product,price:20,status:true}),'restore fixture')
}
console.log('PASS: negative/zero/non-finite/fractional-cent prices rejected with no mutation; repricing, unlisting, version edits and deletion after publication reject confirmation with no quota or order-state change')
