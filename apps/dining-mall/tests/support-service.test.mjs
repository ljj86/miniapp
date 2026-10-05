import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { supportAction as action, supportQuery as query } from '../src/utils/support-service.js'
import { sha256 } from '../src/utils/support-domain.js'

const NOW = Date.parse('2026-10-05T00:00:00Z')
const U = { id: 1, role: 'ROLE_USER' }, U2 = { id: 2, role: 'ROLE_USER' }, M = { id: 1, role: 'ROLE_UNIT' }, M2 = { id: 2, role: 'ROLE_UNIT' }, A = { id: 1, role: 'ROLE_ADMIN' }
const fixture = () => ({ user: [{ id: 1, nickname: '顾客甲' }, { id: 2, nickname: '顾客乙' }], unit: [{ id: 1, nickname: '店一' }, { id: 2, nickname: '店二' }], admin: [{ id: 1, nickname: '平台甲' }], orders: [{ id: 1, userId: 1, unitId: 1, price: 10 }], credit: { balance: 29, history: ['untouched'] }, other: { keep: true } })
let serial = 0
const key = () => `local-test-key-${String(++serial).padStart(6,'0')}`
const call = (db, who, route, payload = {}, token = key()) => action(db,who,route,{...payload,clientKey:token},NOW)
const open = (db, who = U, unitId = '1') => call(db,who,'open-session',{unitId})
const send = (db, who, session, text = '你好', attachmentIds) => call(db,who,'send-message',{sessionId:session.id,text,...(attachmentIds ? {attachmentIds} : {})})
const ticket = (db, extra = {}) => call(db,U,'create-ticket',{unitId:'1',title:'问题',content:'请处理',priority:'MEDIUM',...extra})
const modify = (db, who, row, route, extra = {}) => call(db,who,route,{ticketId:row.id,version:row.version,...extra})
const fileBody = (text = 'plain evidence') => ({name:'evidence.txt',mime:'text/plain',base64:Buffer.from(text).toString('base64')})
const upload = (db, who = U, text) => call(db,who,'attachments/upload',fileBody(text))
const unchanged = (db, fn, code) => { const before = JSON.stringify(db); assert.throws(fn, error => !code || error.code === code); assert.equal(JSON.stringify(db),before) }

test('SHA-256 matches independent Node implementation for empty, Unicode, multi-block and binary inputs', () => {
  for (const input of ['', 'abc', '客服 😀'.repeat(80), new Uint8Array(524288).fill(19)]) assert.equal(sha256(input),createHash('sha256').update(input).digest('hex'))
})
test('queries are detached and empty reads do not seed; identities and strict fields fail atomically', () => {
  const db = fixture(), before = JSON.stringify(db)
  const result = query(db,U,'overview')
  assert.equal(result.ticketTotal,0); assert.equal(result.page,1); assert.equal(result.limit,20); assert.equal(result.counts.archivedTickets,0)
  query(db,A,'context'); assert.equal(JSON.stringify(db),before)
  for (const actor of [null, {}, {id:999,role:'ROLE_USER'}, {id:true,role:'ROLE_USER'}]) unchanged(db,() => open(db,actor),'SUPPORT_AUTH')
  unchanged(db,() => call(db,U,'open-session',{unitId:'1',userId:'2'}))
  unchanged(db,() => action(db,U,'open-session',{unitId:'1',clientKey:'short'}))
  unchanged(db,() => open(db,M),'SUPPORT_FORBIDDEN')
  assert.throws(() => query(db,U,'overview',{limit:15}))
  assert.throws(() => query(db,U,'ticket',{id:'missing',url:'https://example.com'}))
  db.user[0].active = false
  unchanged(db,() => open(db),'SUPPORT_AUTH')
})
test('support scopes, canonical sender, strict spoofing rejection and non-support references remain untouched', () => {
  const db = fixture(), credit = db.credit, orders = db.orders, before = JSON.stringify(db)
  const s = open(db), s2 = open(db,U2), other = open(db,U,'2')
  const msg = send(db,{...U,nickname:'伪造平台'},s,'<b>literal text</b>')
  assert.equal(msg.senderName,'顾客甲'); assert.equal(msg.senderId,'1'); assert.equal(msg.text,'<b>literal text</b>')
  assert.equal(query(db,M,'overview').sessionTotal,2)
  for (const [actor,id] of [[U,s2.id],[U2,s.id],[M2,s.id],[M,other.id]]) assert.throws(() => query(db,actor,'session',{id}),{code:'SUPPORT_FORBIDDEN'})
  unchanged(db,() => call(db,U,'send-message',{sessionId:s.id,text:'x',senderId:'2'}))
  msg.text='changed'; const view=query(db,U,'session',{id:s.id}); view.messages[0].text='changed'; assert.equal(query(db,U,'session',{id:s.id}).messages[0].text,'<b>literal text</b>')
  assert.strictEqual(db.credit,credit); assert.strictEqual(db.orders,orders)
  const {support,...rest}=db; assert.equal(JSON.stringify(rest),before)
})
test('bounded lastSeen cursor preserves concurrent messages, never moves backwards, and belongs to session', () => {
  const db=fixture(), s=open(db), other=open(db,U,'2')
  const a=send(db,U,s,'a'), b=send(db,U,s,'b'), c=send(db,U,other,'other')
  const before=JSON.stringify(db); query(db,M,'session',{id:s.id}); assert.equal(JSON.stringify(db),before)
  assert.equal(call(db,M,'mark-read',{sessionId:s.id,lastSeenMessageId:a.id}).unread,1)
  unchanged(db,() => call(db,M,'mark-read',{sessionId:s.id,lastSeenMessageId:c.id}))
  unchanged(db,() => call(db,M,'mark-read',{sessionId:s.id}))
  assert.equal(call(db,M,'mark-read',{sessionId:s.id,lastSeenMessageId:b.id}).unread,0)
  send(db,U,s,'concurrent')
  assert.equal(call(db,M,'mark-read',{sessionId:s.id,lastSeenMessageId:a.id}).unread,1)
})
test('session pagination returns newest 100 in order without skipped or overlapping older records', () => {
  const db=fixture(), s=open(db)
  for(let i=0;i<105;i++)send(db,U,s,`message ${i}`)
  const latest=query(db,M,'session',{id:s.id}); assert.equal(latest.messages.length,100); assert.equal(latest.hasMore,true); assert.equal(latest.messages[0].text,'message 5')
  const earlier=query(db,M,'session',{id:s.id,beforeId:latest.messages[0].id}); assert.equal(earlier.messages.length,5); assert.equal(earlier.hasMore,false); assert.equal(earlier.messages.at(-1).text,'message 4')
  assert.equal(new Set([...earlier.messages,...latest.messages].map(row=>row.id)).size,105)
  assert.equal(query(db,M,'overview').counts.unreadMessages,105)
})
test('title uses 200 codepoints, legacy priority normalizes; orders remain scoped', () => {
  const db=fixture()
  assert.equal(ticket(db,{title:'😀'.repeat(200),priority:'IMPORTANT',orderId:'1'}).priority,'HIGH')
  assert.equal(ticket(db,{priority:'NORMAL'}).priority,'MEDIUM')
  assert.equal(ticket(db,{priority:'LOW'}).priority,'LOW')
  for(const extra of [{title:'😀'.repeat(201)},{content:'a'.repeat(1501)},{priority:'URGENT'},{orderId:'9'}]) unchanged(db,()=>ticket(db,extra))
})
test('upload is private until bound; metadata never leaks bytes or uploader; post-binding scope is current', () => {
  const db=fixture(), s=open(db), f=upload(db)
  assert.match(f.id,/^\d+$/); assert.equal(f.securityStatus,'UNSCANNED_SIMULATION'); assert.equal(f.base64,undefined); assert.equal(f.uploaderKey,undefined)
  for(const actor of [M,A,U2])assert.throws(()=>query(db,actor,'attachments/detail',{id:f.id}),{code:'SUPPORT_FORBIDDEN'})
  const msg=send(db,U,s,'',[f.id]); assert.equal(msg.attachments.length,1); assert.equal(msg.attachment.resourceType,'session')
  assert.equal(query(db,M,'attachments/download',{id:f.id}).base64,fileBody().base64)
  assert.throws(()=>query(db,M2,'attachments/detail',{id:f.id}),{code:'SUPPORT_FORBIDDEN'})
  unchanged(db,()=>send(db,M,s,'',[f.id]),'SUPPORT_FORBIDDEN')
  const other = open(db,U,'2')
  unchanged(db,()=>send(db,U,other,'', [f.id]),'ATTACHMENT_ALREADY_BOUND')
})
test('all attachments validate before binding; duplicate, sixth, foreign and moved IDs reject without partial mutation', () => {
  const db=fixture(), s=open(db), f=upload(db), foreign=upload(db,U2)
  for(const ids of [[f.id,f.id],[f.id,foreign.id],[f.id,'99999']]) unchanged(db,()=>send(db,U,s,'valid',ids))
  const files=Array.from({length:6},()=>upload(db))
  unchanged(db,()=>send(db,U,s,'valid',files.map(row=>row.id)))
  assert.equal(query(db,U,'attachments/detail',{id:f.id}).resourceId,undefined)
  assert.equal(send(db,U,s,'valid',files.slice(0,5).map(row=>row.id)).attachments.length,5)
  assert.equal(db.support.messages.length,1)
})
test('strict MIME/name/base64 validation rejects remote paths, disguised HTML, malformed UTF8 and bad PNG CRC', () => {
  const db=fixture()
  const bad=[{...fileBody(),url:'https://x'},{...fileBody(),name:'../x.txt'},{...fileBody(),name:'script.svg'},{...fileBody(),name:'bad\u202ename.txt'},{...fileBody(),base64:'YQ'},{...fileBody(),base64:'YR=='},{...fileBody(),base64:'data:text/plain;base64,YQ=='},{...fileBody(),base64:'Y Q=='},{...fileBody(),base64:'_Q=='},{...fileBody(),base64:'/w=='},fileBody('<html>disguised</html>'),{name:'x.png',mime:'image/png',base64:Buffer.from('not an image').toString('base64')},{...fileBody(),mime:'image/svg+xml'}]
  for(const body of bad) unchanged(db,()=>call(db,U,'attachments/upload',body))
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=','base64')
  const file=call(db,U,'attachments/upload',{name:'one.png',mime:'image/png',base64:png.toString('base64')}); assert.equal(file.size,png.length)
  png[20]^=1; unchanged(db,()=>call(db,U,'attachments/upload',{name:'bad.png',mime:'image/png',base64:png.toString('base64')}),'ATTACHMENT_TYPE_MISMATCH')
})
test('1 MiB lifetime local quota is explicit and upload retries do not consume it twice', () => {
  const db=fixture(), body=fileBody('a'.repeat(524288)), token=key()
  const first=call(db,U,'attachments/upload',body,token), before=JSON.stringify(db)
  assert.deepEqual(call(db,U,'attachments/upload',body,token),first); assert.equal(JSON.stringify(db),before)
  upload(db,U2,'b'.repeat(524288))
  unchanged(db,()=>upload(db,U,'one byte'),'ATTACHMENT_QUOTA_EXCEEDED')
  unchanged(db,()=>upload(db,U,'a'.repeat(524289)))
  assert.equal(db.support.attachments.length,2)
})
test('archive works in every status; merchants lose ticket and attachment access; restore preserves status/history', () => {
  for(const status of ['OPEN','IN_PROGRESS','RESOLVED','CLOSED']) {
    const db=fixture(), f=upload(db), merchantFile=upload(db,M)
    let row=ticket(db,{attachmentIds:[f.id]})
    row=modify(db,M,row,'reply-ticket',{text:'merchant evidence',attachmentIds:[merchantFile.id]})
    for(const step of ['IN_PROGRESS','RESOLVED','CLOSED']) { if(row.status===status)break; row=modify(db,M,row,'ticket-status',{status:step}) }
    unchanged(db,()=>modify(db,M,row,'archive-ticket',{reason:'merchant'}),'SUPPORT_FORBIDDEN')
    const oldHistory=structuredClone(row.history)
    row=modify(db,U,row,'archive-ticket',{reason:'customer archive'})
    assert.equal(row.status,status); assert.equal(row.canReply,false); assert.equal(row.canRestore,true); assert.deepEqual(row.history.slice(0,-1),oldHistory)
    assert.equal(query(db,M,'overview',{includeArchived:true}).ticketTotal,0)
    for(const who of [M,U2])assert.throws(()=>query(db,who,'ticket',{id:row.id}),{code:'SUPPORT_FORBIDDEN'})
    for(const f2 of [f,merchantFile])assert.throws(()=>query(db,M,'attachments/download',{id:f2.id}),{code:'SUPPORT_FORBIDDEN'})
    unchanged(db,()=>modify(db,U,row,'reply-ticket',{text:'blocked'}),'SUPPORT_ARCHIVED')
    assert.equal(query(db,U,'overview').ticketTotal,0); assert.equal(query(db,U,'overview').counts.archivedTickets,1)
    row=modify(db,A,row,'restore-ticket',{reason:'restore'}); assert.equal(row.status,status); assert.equal(row.archived,false); assert.equal(row.history.at(-1).type,'RESTORE')
    assert.equal(query(db,M,'attachments/download',{id:merchantFile.id}).id,merchantFile.id)
  }
})
test('reply/status versions, immutable history and idempotency survive JSON persistence and staff reopen', () => {
  let db=fixture(), row=ticket(db); const original=structuredClone(row.history), token=key()
  const body={ticketId:row.id,version:row.version,text:'first'}
  row=call(db,M,'reply-ticket',body,token)
  unchanged(db,()=>modify(db,M,{...row,version:1},'reply-ticket',{text:'stale'}),'SUPPORT_VERSION_CONFLICT')
  unchanged(db,()=>call(db,M,'reply-ticket',{ticketId:row.id,text:'missing version'}))
  row=modify(db,M,row,'ticket-status',{status:'IN_PROGRESS'}); row=modify(db,A,row,'ticket-status',{status:'RESOLVED'}); row=modify(db,U,row,'ticket-status',{status:'CLOSED'})
  db=JSON.parse(JSON.stringify(db)); const before=JSON.stringify(db)
  assert.equal(call(db,M,'reply-ticket',body,token).status,'CLOSED'); assert.equal(JSON.stringify(db),before)
  unchanged(db,()=>call(db,M,'reply-ticket',{...body,text:'changed'},token),'SUPPORT_IDEMPOTENCY_CONFLICT')
  row=modify(db,M,row,'ticket-status',{status:'OPEN'}); assert.equal(row.status,'OPEN'); assert.deepEqual(row.history.slice(0,1),original)
  row.history[0].text='mutated response'; assert.deepEqual(query(db,U,'ticket',{id:row.id}).history.slice(0,1),original)
})
test('overview totals and scoped counts cover filters and pagination independently of returned page', () => {
  const db=fixture()
  for(let i=0;i<23;i++)ticket(db,{priority:i%2?'HIGH':'LOW'})
  const page1=query(db,U,'overview',{limit:10}), page3=query(db,U,'overview',{limit:'10',page:'3'})
  assert.equal(page1.tickets.length,10); assert.equal(page1.ticketTotal,23); assert.equal(page1.hasMore,true); assert.equal(page1.truncated,true)
  assert.equal(page3.tickets.length,3); assert.equal(page3.hasMore,false); assert.equal(page3.counts.openTickets,23)
  assert.equal(query(db,U,'overview',{priority:'IMPORTANT'}).ticketTotal,11)
  assert.equal(query(db,M2,'overview').ticketTotal,0)
})
test('overview search and shop filters apply after access scope and context includes non-mutating order labels', () => {
  const db=fixture(); ticket(db,{title:'特殊问题'}); ticket(db,{unitId:'2',title:'别店问题'}); open(db); open(db,U,'2')
  assert.equal(query(db,U,'overview',{q:'特殊'}).ticketTotal,1)
  assert.equal(query(db,M2,'overview',{q:'特殊'}).ticketTotal,0)
  const filtered=query(db,U,'overview',{q:'店二',unitId:'2'}); assert.equal(filtered.sessionTotal,1); assert.equal(filtered.ticketTotal,1); assert.equal(filtered.counts.tickets,1)
  assert.equal(query(db,M,'overview',{unitId:'2'}).sessionTotal,0)
  assert.throws(()=>query(db,U,'overview',{q:' '})); assert.throws(()=>query(db,U,'overview',{unitId:2})); assert.throws(()=>query(db,U,'overview',{q:'a'.repeat(101)}))
  const order=query(db,U,'context').orders[0]; assert.equal(order.totalMinor,1000); assert.equal(order.name,'本机模拟订单 1')
})
test('attachment metadata count quota stops many tiny uploads and does not count retries twice', () => {
  const db=fixture(), token=key(), body=fileBody('x')
  call(db,U,'attachments/upload',body,token)
  for(let i=1;i<128;i++)upload(db,U,'x')
  call(db,U,'attachments/upload',body,token)
  unchanged(db,()=>upload(db,U,'x'),'ATTACHMENT_QUOTA_EXCEEDED')
  assert.equal(upload(db,U2,'x').size,1)
})
test('matching bounded signature validators accept all seven MIME families and reject container truncations', () => {
  const db=fixture()
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=','base64')
  const jpeg=Buffer.from([255,216,255,192,0,11,8,0,1,0,1,1,1,17,0,255,218,0,8,1,1,0,0,63,0,0,255,217])
  const gif=Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7','base64')
  const webp=Buffer.alloc(26); webp.write('RIFF'); webp.writeUInt32LE(18,4); webp.write('WEBPVP8L',8); webp.writeUInt32LE(5,16); webp[20]=47
  const mp4=Buffer.alloc(36); mp4.writeUInt32BE(24); mp4.write('ftypisom',4); mp4.write('isommp41',16); mp4.writeUInt32BE(12,24); mp4.write('mdat',28)
  const formats=[['image/png',png],['image/jpeg',jpeg],['image/gif',gif],['image/webp',webp],['application/pdf',Buffer.from('%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n')],['text/plain',Buffer.from('UTF-8 合成测试')],['video/mp4',mp4]]
  for(const [mime,bytes] of formats) {
    const result=call(db,U,'attachments/upload',{name:'evidence.bin',mime,base64:bytes.toString('base64')}); assert.equal(result.mime,mime); assert.equal(result.sha256,createHash('sha256').update(bytes).digest('hex'))
    if(mime!=='text/plain')unchanged(db,()=>call(db,U,'attachments/upload',{name:'bad.bin',mime,base64:bytes.subarray(0,8).toString('base64')}),'ATTACHMENT_TYPE_MISMATCH')
  }
})
test('invalid ticket create/reply with attachments leaves existing uploads unbound and all history unchanged', () => {
  const db=fixture(), f=upload(db), foreign=upload(db,U2)
  unchanged(db,()=>ticket(db,{attachmentIds:[f.id,foreign.id]}),'SUPPORT_FORBIDDEN')
  assert.equal(db.support.tickets.length,0); assert.equal(query(db,U,'attachments/detail',{id:f.id}).resourceId,undefined)
  const row=ticket(db)
  unchanged(db,()=>modify(db,U,row,'reply-ticket',{text:'',attachmentIds:[f.id]}))
  unchanged(db,()=>modify(db,U,row,'reply-ticket',{text:'valid',attachmentIds:[f.id,foreign.id]}),'SUPPORT_FORBIDDEN')
  assert.equal(query(db,U,'attachments/detail',{id:f.id}).resourceId,undefined); assert.equal(query(db,U,'ticket',{id:row.id}).historyCount,1)
})
test('legacy inline attachment and priority migrate only detached projection; history is never rewritten', () => {
  const db=fixture(); let row=ticket(db); const attachment={name:'old.png',mime:'image/png',size:33,dataUrl:'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII='}
  db.support.tickets[0].attachment=attachment; delete db.support.tickets[0].attachmentIds; db.support.tickets[0].priority='NORMAL'; delete db.support.tickets[0].archived
  const history=structuredClone(db.support.tickets[0].history), before=JSON.stringify(db)
  row=query(db,U,'ticket',{id:row.id}); assert.equal(row.priority,'MEDIUM'); assert.equal(row.attachment.dataUrl,attachment.dataUrl); assert.equal(row.attachment.legacyInline,true); assert.equal(JSON.stringify(db),before)
  row=modify(db,M,row,'reply-ticket',{text:'new schema reply'})
  assert.deepEqual(row.history.slice(0,1),history); assert.equal(db.support.tickets[0].attachment.dataUrl,attachment.dataUrl); assert.equal(row.priority,'MEDIUM')
})
test('unknown future schema and exhausted aggregate storage fail without migration or lost history', () => {
  const db=fixture(); open(db)
  db.support.schemaVersion=999
  unchanged(db,()=>open(db),'SUPPORT_SCHEMA'); assert.throws(()=>query(db,U,'overview'),{code:'SUPPORT_SCHEMA'})
  db.support.schemaVersion=1
  db.support.audit.push({legacyNote:'x'.repeat(2*1024*1024)})
  unchanged(db,()=>ticket(db),'LOCAL_STORAGE_BUDGET_EXCEEDED')
  assert.equal(db.support.tickets.length,0); assert.equal(db.support.audit.length,1)
})
