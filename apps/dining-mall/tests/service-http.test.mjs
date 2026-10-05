import assert from 'node:assert/strict'
import {serviceBaseUrl,createServiceHttpClient} from '../src/utils/service-http.js'
assert.equal(serviceBaseUrl('http://localhost:18080'),'http://localhost:18080/api/v1')
for(const url of ['http://public.example.com','https://user:pass@example.com','https://example.com/?key=x','file:///tmp'])assert.throws(()=>serviceBaseUrl(url))
let requests=[];let next=new Response(JSON.stringify({data:{id:'ST-server'},meta:{environment:'SIMULATION'}}),{status:200,headers:{'content-type':'application/json'}})
const client=createServiceHttpClient({baseUrl:'http://localhost:18080',tokenProvider:()=> 'test-only-memory-token',fetchImpl:async(url,options)=>{requests.push({url,options});return next}})
let r=await client.post('/support/create-ticket',{unitId:'STORE-real',clientKey:'a-stable-request-key-123',title:'title'})
assert.equal(r.code,'200');assert.equal(requests[0].options.headers['Idempotency-Key'],'a-stable-request-key-123');assert.equal(requests[0].options.headers.Authorization,'Bearer test-only-memory-token');assert.ok(!requests[0].options.body.includes('clientKey'));assert.ok(requests[0].options.body.includes('STORE-real'));assert.equal(requests[0].options.redirect,'error')
next=new Response(JSON.stringify({code:'VERSION_CONFLICT',message:'版本已变化',requestId:'req'}),{status:409});r=await client.post('/support/ticket-status',{version:1,clientKey:'same-action-request-key'});assert.equal(r.code,'409');assert.equal(r.backendCode,'VERSION_CONFLICT')
const offline=createServiceHttpClient({baseUrl:'http://localhost:18080',tokenProvider:()=> 'test',fetchImpl:async()=>{throw Error('offline')}});assert.equal((await offline.get('/support/overview')).code,'503')
const guest=createServiceHttpClient({baseUrl:'http://localhost:18080',fetchImpl:()=>{throw Error('must not fetch')}});assert.equal((await guest.get('/support/overview')).code,'401')
assert.equal((await client.post('/auth/sms-challenges',{phone:'13800000000'},{anonymous:true})).code,'400')
next=new Response(JSON.stringify({data:{},meta:{environment:'PRODUCTION'}}),{status:200});assert.equal((await client.get('/support/overview')).code,'502')
console.log('PASS HTTP adapter: protocol/auth/idempotency, no credential URLs or real SMS, explicit errors and no demo fallback')
