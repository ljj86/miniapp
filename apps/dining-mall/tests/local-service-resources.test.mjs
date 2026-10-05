import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { supportResourceQuery as query, supportResourceAction as action } from '../src/utils/local-service-resources.js'
import { supportAction, supportQuery } from '../src/utils/support-service.js'
import { canonical } from '../src/utils/support-domain.js'
const NOW=Date.parse('2026-10-05T00:00:00Z'), DATE=new Date(NOW).toISOString()
const U={id:1,role:'ROLE_USER'}, M={id:1,role:'ROLE_UNIT'}, A={id:1,role:'ROLE_ADMIN'}
const fixture=()=>({user:[{id:1}],unit:[{id:1}],admin:[{id:1}],credit:{balance:7000},orders:[{id:1,amount:8}]})
const builtins=[
  {id:'1',kind:'MANUAL',title:'顾客手册',content:'顾客合成示例',audience:'CUSTOMER',createdAt:DATE,updatedAt:DATE},
  {id:'2',kind:'MANUAL',title:'商家手册',content:'商家合成示例',audience:'MERCHANT',createdAt:DATE,updatedAt:DATE},
  {id:'3',kind:'MANUAL',title:'平台手册',content:'平台合成示例',audience:'ALL',createdAt:DATE,updatedAt:DATE},
  {id:'4',kind:'KNOWLEDGE',title:'还款知识',content:'合成知识',audience:'ALL',createdAt:DATE,updatedAt:DATE},
  {id:'5',kind:'KNOWLEDGE',title:'附件知识',content:'文件未经安全扫描',audience:'ALL',createdAt:DATE,updatedAt:DATE},
  {id:'6',kind:'UPDATE_LOG',title:'源码能力记录',content:'SOURCE_IMPLEMENTATION，仅测试清单，并非发布或部署证明',audience:'ALL',createdAt:DATE,updatedAt:DATE}
]
let serial=0
const key=()=>`resource-test-key-${++serial}`
const call=(db,route,body={},params={},token=key(),actor=A)=>action(db,actor,route,{...body,clientKey:token},params,builtins,NOW)
const get=(db,who,route,params={})=>query(db,who,route,params,builtins)
const material={kind:'KNOWLEDGE',title:'自定义知识',content:'纯文本 <b>不是HTML</b>',audience:'ALL'}
const create=(db,extras={},token)=>call(db,'resources/create',{...material,...extras},{},token)
const unchanged=(db,fn,code)=>{const before=JSON.stringify(db);assert.throws(fn,error=>!code||error.code===code);assert.equal(JSON.stringify(db),before)}

test('source-injected builtins are deterministic and detached; reads/defaults never create state',()=>{
  const db=fixture(), before=JSON.stringify(db), source=JSON.stringify(builtins)
  const first=get(db,U,'resources'); assert.equal(first.length,6); assert.equal(first[0].readOnly,true)
  assert.equal(first.find(row=>row.id==='6').source,'SOURCE_IMPLEMENTATION'); assert.equal(first[0].contentFormat,'PLAIN_TEXT')
  first[0].content='changed'; assert.notEqual(get(db,U,'resources')[0].content,'changed')
  assert.equal(get(db,A,'resources/history',{id:'1'}).length,0)
  assert.equal(get(db,A,'platform/ai').version,1); assert.equal(get(db,A,'platform/payments').length,4)
  assert.equal(JSON.stringify(db),before); assert.equal(JSON.stringify(builtins),source)
  assert.throws(()=>query(db,U,'resources',{},[{...builtins[0],createdAt:undefined}]),{code:'RESOURCE_MANIFEST_INVALID'})
})
test('audience categorizes content, not security; forced manual/update kinds and search match',()=>{
  const db=fixture()
  assert.equal(get(db,U,'manual',{id:'2'}).audience,'MERCHANT')
  assert.equal(get(db,M,'resources',{audience:'CUSTOMER'}).length,5)
  assert.equal(get(db,U,'manuals').length,3); assert.equal(get(db,U,'updates').length,1)
  assert.equal(get(db,U,'resources',{q:'扫描'}).length,1)
  assert.throws(()=>get(db,U,'manual',{id:'5'}),{code:'RESOURCE_NOT_FOUND'})
  for(const params of [{q:''},{q:'x'.repeat(101)},{audience:'ADMIN'},{kind:'HTML'},{url:'https://example.com'},{status:'ARCHIVED'}])assert.throws(()=>get(db,U,'resources',params))
  assert.throws(()=>get(db,U,'manuals',{kind:'KNOWLEDGE'}))
})
test('all writes, admin lists/history, and configuration require actual platform role',()=>{
  const db=fixture()
  for(const who of [U,M]) {
    for(const route of ['admin/resources','resources/history','platform/ai','platform/payments'])assert.throws(()=>get(db,who,route,route==='resources/history'?{id:'1'}:{}),{code:'SUPPORT_FORBIDDEN'})
    unchanged(db,()=>call(db,'resources/create',material,{},key(),{...who,globalSupport:true}),'SUPPORT_FORBIDDEN')
  }
  db.admin[0].active=false; unchanged(db,()=>create(db),'SUPPORT_AUTH')
})
test('custom resource create/update/archive retain complete immutable canonical hash snapshots and scoped visibility',()=>{
  const db=fixture(), before=JSON.stringify(db), credit=db.credit, orders=db.orders
  let row=create(db); assert.match(row.id,/^\d+$/); assert.equal(row.version,1); assert.equal(row.readOnly,false)
  const original=get(db,A,'resources/history',{id:row.id})
  row=call(db,'resources/update',{...material,title:'新标题',version:1},{id:row.id})
  row=call(db,'resources/archive',{version:2},{id:row.id})
  assert.equal(row.status,'ARCHIVED'); assert.equal(row.version,3); assert.equal(get(db,U,'resources').length,6)
  assert.equal(get(db,A,'admin/resources',{status:'ARCHIVED'}).length,1)
  assert.throws(()=>get(db,U,'resource',{id:row.id}),{code:'RESOURCE_NOT_FOUND'})
  assert.equal(get(db,A,'resource',{id:row.id}).status,'ARCHIVED')
  const history=get(db,A,'resources/history',{id:row.id}); assert.deepEqual(history.map(row=>row.action),['CREATED','UPDATED','ARCHIVED']); assert.deepEqual(history.slice(0,1),original)
  for(const event of history)assert.equal(event.snapshotHash,createHash('sha256').update(canonical(event.snapshot)).digest('hex'))
  history[0].snapshot.content='changed'; assert.deepEqual(get(db,A,'resources/history',{id:row.id}).slice(0,1),original)
  unchanged(db,()=>call(db,'resources/update',{...material,version:3},{id:row.id}),'STATE_CONFLICT')
  assert.equal(db.support.audit.length,3); assert.strictEqual(db.credit,credit); assert.strictEqual(db.orders,orders)
  const {support,...rest}=db; assert.equal(JSON.stringify(rest),before)
})
test('unchanged content, stale versions, missing full material, unknown fields, readonly and oversized input reject atomically',()=>{
  const db=fixture(), row=create(db)
  unchanged(db,()=>call(db,'resources/update',{...material,version:1},{id:row.id}),'MATERIAL_UNCHANGED')
  unchanged(db,()=>call(db,'resources/update',{...material,title:'x',version:2},{id:row.id}),'SUPPORT_VERSION_CONFLICT')
  unchanged(db,()=>call(db,'resources/update',{title:'x',version:1},{id:row.id}))
  unchanged(db,()=>call(db,'resources/archive',{version:1},{id:'1'}),'READ_ONLY_RESOURCE')
  unchanged(db,()=>create(db,{url:'https://example.com'}))
  for(const extras of [{title:'x'.repeat(161)},{content:'x'.repeat(16001)},{content:'\u0080'},{title:''}])unchanged(db,()=>create(db,extras))
  unchanged(db,()=>call(db,'resources/update',{...material,title:'x',version:1,id:row.id},{}))
})
test('AI metadata cannot become connected, store keys/addresses or make external calls, including first update',()=>{
  const db=fixture(), before=JSON.stringify(db), current=get(db,A,'platform/ai')
  assert.equal(current.model,'unconfigured'); assert.equal(current.enabled,false); assert.equal(JSON.stringify(db),before)
  const body={version:1,enabled:true,model:'demo:model-1',welcomeMsg:'纯文本欢迎语'}, token=key()
  const result=call(db,'platform/ai',body,{},token)
  assert.equal(result.version,2); assert.equal(result.enabled,true); assert.equal(result.status,'NOT_CONNECTED'); assert.equal(result.externalCallsEnabled,false); assert.equal(result.secretConfigured,false)
  const snapshot=JSON.stringify(db); assert.deepEqual(call(db,'platform/ai',body,{},token),result); assert.equal(JSON.stringify(db),snapshot)
  unchanged(db,()=>call(db,'platform/ai',{...body,model:'stale'}),'SUPPORT_VERSION_CONFLICT')
  for(const extras of [{model:'sk-secret'},{model:'https://api.example.com'},{model:'/path'},{enabled:'true'},{apiKey:'secret'},{welcomeMsg:'x'.repeat(501)},{version:2.1}])unchanged(db,()=>call(db,'platform/ai',{...body,version:2,...extras}))
  const event=db.support.platformConfigHistory[0]; assert.equal(event.configVersion,2); assert.equal(event.snapshotHash,createHash('sha256').update(canonical(event.snapshot)).digest('hex'))
})
test('payment display configuration preserves MOCK-only ready and all channels non-charging',()=>{
  const db=fixture(), defaults=get(db,A,'platform/payments')
  assert.deepEqual(defaults.map(row=>row.id),['MOCK','ALIPAY','WECHAT','BANKCARD']); assert.deepEqual(defaults.map(row=>row.ready),[true,false,false,false])
  for(const id of ['MOCK','ALIPAY','WECHAT','BANKCARD']) {
    const result=call(db,'platform/payments',{version:1,displayEnabled:true,label:'自定义名称'},{id})
    assert.equal(result.version,2); assert.equal(result.ready,id==='MOCK'); assert.equal(result.connected,false); assert.equal(result.chargesEnabled,false); assert.equal(result.externalCallsEnabled,false); assert.equal(result.secretConfigured,false)
  }
  unchanged(db,()=>call(db,'platform/payments',{version:2,displayEnabled:true,label:'x',connected:true},{id:'ALIPAY'}))
  unchanged(db,()=>call(db,'platform/payments',{version:2,displayEnabled:true,label:'x'},{id:'BANK'}))
  assert.equal(db.support.platformConfigHistory.length,4); assert.equal(db.support.audit.length,4)
})
test('idempotency survives persistence, is bound to actor/action/body/query, and resource replay returns current state',()=>{
  let db=fixture(), token=key(), row=create(db,{},token)
  row=call(db,'resources/update',{...material,title:'updated',version:1},{id:row.id})
  db=JSON.parse(JSON.stringify(db)); const before=JSON.stringify(db)
  assert.equal(create(db,{},token).title,'updated'); assert.equal(JSON.stringify(db),before)
  unchanged(db,()=>create(db,{title:'conflict'},token),'SUPPORT_IDEMPOTENCY_CONFLICT')
  unchanged(db,()=>call(db,'platform/ai',{version:1,enabled:false,model:'demo',welcomeMsg:'welcome'},{},token),'SUPPORT_IDEMPOTENCY_CONFLICT')
  const archived=call(db,'resources/archive',{version:2},{id:row.id}); assert.equal(archived.version,3)
  assert.equal(create(db,{},token).status,'ARCHIVED')
})
test('support and resources share safe aggregate sequence and commits without overwriting each other',()=>{
  const db=fixture(), row=create(db)
  const s=supportAction(db,U,'open-session',{unitId:'1',clientKey:key()},NOW)
  assert.notEqual(row.id,s.id); assert.equal(get(db,U,'resource',{id:row.id}).id,row.id)
  call(db,'platform/ai',{version:1,enabled:true,model:'example',welcomeMsg:'hello'})
  assert.equal(supportQuery(db,U,'session',{id:s.id}).session.id,s.id)
  assert.equal(db.support.resourceHistory.length,1)
})
test('own display profile is separate, versioned, idempotent and never exposes contacts to other support users',()=>{
  const db=fixture(); db.user[0]={id:1,username:'111',nickname:'原顾客',phone:'13812345678'}; db.orders[0].userId=1; db.orders[0].unitId=1
  const authBefore=JSON.stringify(db.user), financeBefore=JSON.stringify(db.credit), before=JSON.stringify(db)
  const current=get(db,U,'profile'); assert.equal(current.loginPhone,'138****5678'); assert.equal(current.identityReadOnly,true); assert.equal(JSON.stringify(db),before)
  const body={version:1,nickname:'新顾客',avatarUrl:'/avatar.svg',email:'local@example.test',contactPhone:'+86 (138) 1234-5678'}, token=key()
  const result=call(db,'profile',body,{},token,U); assert.equal(result.version,2); assert.equal(result.username,'111'); assert.equal(result.nickname,'新顾客')
  const saved=JSON.stringify(db); assert.deepEqual(call(db,'profile',body,{},token,U),result); assert.equal(JSON.stringify(db),saved)
  assert.equal(JSON.stringify(db.user),authBefore); assert.equal(JSON.stringify(db.credit),financeBefore)
  assert.equal(get(db,M,'profile').email,''); assert.equal(get(db,A,'profile').email,'')
  const session=supportAction(db,U,'open-session',{unitId:'1',clientKey:key()},NOW)
  const message=supportAction(db,U,'send-message',{sessionId:session.id,text:'hello',clientKey:key()},NOW)
  assert.equal(message.senderName,'新顾客'); const dto=supportQuery(db,M,'session',{id:session.id}); assert.equal(dto.session.customerName,'新顾客'); assert.equal(JSON.stringify(dto).includes('local@example.test'),false); assert.equal(JSON.stringify(dto).includes('1234-5678'),false)
  for(const extra of [{avatarUrl:'https://example.com/image.png'},{email:'broken'},{contactPhone:'drop table'},{nickname:'a'.repeat(81)},{nickname:'bad\nname'},{userId:'2'}])unchanged(db,()=>call(db,'profile',{...body,version:2,...extra},{},key(),U))
  unchanged(db,()=>call(db,'profile',{...body,nickname:'stale'},{},key(),U),'SUPPORT_VERSION_CONFLICT')
})
test('resource quota counts archived entries and rejects the next material atomically',()=>{
  const db=fixture(), row=create(db)
  db.support.resources=Array.from({length:500},(_,index)=>({...row,id:String(1000+index),status:index%2?'ARCHIVED':'ACTIVE'}))
  db.support.sequence=1600
  unchanged(db,()=>create(db),'RESOURCE_QUOTA_EXCEEDED')
  assert.equal(db.support.resources.length,500); assert.equal(db.support.resourceHistory.length,1)
})
