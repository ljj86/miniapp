import assert from 'node:assert/strict'
import {refreshHistory} from '../src/utils/service-history.js'
const rows=(a,b)=>Array.from({length:b-a+1},(_,i)=>({id:String(a+i),sequence:a+i,createdAt:'2026-10-01T00:00:00Z'}))
let calls=[]
const out=await refreshHistory({prior:rows(1,200),latest:{messages:rows(251,350),hasMore:true},fetchOlder:async id=>{calls.push(id);return {messages:rows(151,250),hasMore:true}}})
assert.equal(out.messages.length,350);assert.deepEqual(out.messages.map(x=>x.id),rows(1,350).map(x=>x.id));assert.deepEqual(calls,['251']);assert.equal(out.readable,true);assert.equal(out.reset,false)
const bounded=await refreshHistory({prior:rows(1,100),latest:{messages:rows(1001,1100),hasMore:true},maxPages:2,fetchOlder:async id=>({messages:rows(Number(id)-100,Number(id)-1),hasMore:true})});assert.equal(bounded.reset,true);assert.equal(bounded.readable,false);assert.deepEqual(bounded.messages,rows(1001,1100))
const stale=await refreshHistory({prior:rows(1,100),latest:{messages:rows(201,300),hasMore:true},current:()=>false,fetchOlder:async()=>({messages:rows(101,200),hasMore:true})});assert.equal(stale.readable,false)
console.log('PASS message history gap filled before read cursor; bounded and stale responses do not advance read state')
