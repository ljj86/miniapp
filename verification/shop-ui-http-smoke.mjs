// Real HTTP proof using the actual dining front-end transport, never a mock fetch.
import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {pathToFileURL} from 'node:url'
import path from 'node:path'
const modulePath=process.argv[2]||path.resolve('apps/dining-mall/src/utils/service-http.js')
const {createServiceHttpClient}=await import(pathToFileURL(modulePath).href)
const baseUrl=process.env.SHOP_API_BASE
assert.ok(baseUrl,'Run with verification/with-support-http-fixture.py')
const ok=result=>{assert.equal(result.code,'200',`${result.backendCode||''}: ${result.msg}`);return result.data}
async function actor(phone){let token='';const client=createServiceHttpClient({baseUrl,tokenProvider:()=>token});const challenge=ok(await client.post('/auth/sms-challenges',{phone,purpose:'LOGIN'},{anonymous:true}));const session=ok(await client.post('/auth/sessions',{provider:'PHONE_OTP',phone,challengeId:challenge.challengeId,code:'246810'},{anonymous:true}));token=session.accessToken;assert.ok(token);return client}
const [user,owner,other,platform]=await Promise.all(['SIM-USER-001','SIM-OWNER-001','SIM-USER-002','SIM-SUPPORT-001'].map(actor))
const context=ok(await user.get('/support/context'));assert.equal(context.actor.role,'ROLE_USER');assert.equal(context.actor.id,'101');const shop=context.shops[0].unitId;assert.equal(typeof shop,'string')
assert.equal(ok(await owner.get('/support/context')).actor.role,'ROLE_UNIT');assert.equal(ok(await platform.get('/support/context')).actor.role,'ROLE_ADMIN')
console.log('PASS actual UI transport → Java authenticated context and roles')
const session=ok(await user.post('/support/open-session',{unitId:shop}));const id=session.id
const bytes=Buffer.from('synthetic attachment via actual frontend transport','utf8')
const attachment=ok(await user.post('/support/attachments/upload',{name:'synthetic.txt',mime:'text/plain',base64:bytes.toString('base64')}));assert.equal(attachment.securityStatus,'UNSCANNED_SIMULATION');assert.ok(!('base64' in attachment))
const messageBody={sessionId:id,text:'顾客通过真实HTTP发送',attachmentIds:[attachment.id]};const key='live-smoke-message-idempotency-key';const message=ok(await user.post('/support/send-message',messageBody,{clientKey:key}));assert.deepEqual(message,ok(await user.post('/support/send-message',messageBody,{clientKey:key})))
let thread=ok(await owner.get('/support/session',{params:{id}}));assert.equal(thread.messages.length,1);assert.equal(thread.session.unread,1);assert.equal(thread.messages[0].senderId,context.actor.id)
ok(await owner.post('/support/mark-read',{sessionId:id,lastSeenMessageId:message.id}));ok(await owner.post('/support/send-message',{sessionId:id,text:'商家通过真实HTTP回复'}))
thread=ok(await user.get('/support/session',{params:{id}}));assert.equal(thread.messages.length,2);assert.equal(thread.session.unread,1)
assert.equal((await other.get('/support/session',{params:{id}})).code,'404');assert.equal((await other.get('/support/attachments/download',{params:{id:attachment.id}})).code,'404')
const download=ok(await owner.get('/support/attachments/download',{params:{id:attachment.id}}));assert.equal(download.base64,bytes.toString('base64'));assert.equal(download.sha256,createHash('sha256').update(bytes).digest('hex'))
assert.equal((await user.post('/support/send-message',{sessionId:id,text:'spoof',senderId:'1'})).code,'422')
console.log('PASS shop-bound send/reply/unread, idempotency, authenticated attachment bytes and cross-user denial')
let ticket=ok(await user.post('/support/create-ticket',{unitId:shop,title:'HTTP工单',content:'实际传输与服务器持久化',priority:'HIGH'}));assert.equal(ticket.detailRequired,true);assert.ok(!('history' in ticket));const ticketId=ticket.id
const detail=async client=>ok(await client.get('/support/ticket',{params:{id:ticketId}}))
ticket=await detail(user);ticket=ok(await owner.post('/support/reply-ticket',{ticketId,version:ticket.version,text:'实际商家回复'}));assert.equal(ticket.replyCount,1);assert.ok(!('replies' in ticket));ticket=await detail(user);assert.equal(ticket.replies.length,1)
assert.equal((await owner.post('/support/reply-ticket',{ticketId,version:1,text:'stale overwrite'})).code,'409')
for(const status of ['IN_PROGRESS','RESOLVED'])ticket=ok(await owner.post('/support/ticket-status',{ticketId,version:ticket.version,status}))
ticket=ok(await user.post('/support/ticket-status',{ticketId,version:ticket.version,status:'CLOSED'}))
ticket=ok(await user.post('/support/archive-ticket',{ticketId,version:ticket.version,reason:'本人收起'}));assert.equal(ticket.archived,true);assert.equal((await owner.get('/support/ticket',{params:{id:ticketId}})).code,'404')
assert.equal(ok(await user.get('/support/overview')).ticketTotal,0);const archive=ok(await user.get('/support/overview',{params:{includeArchived:true,unitId:shop,q:'HTTP',limit:10,page:1}}));assert.equal(archive.ticketTotal,1);assert.ok(!('history' in archive.tickets[0]))
ticket=ok(await user.post('/support/restore-ticket',{ticketId,version:ticket.version,reason:'恢复查看'}));assert.equal(ticket.archived,false);ticket=ok(await user.post('/support/ticket-status',{ticketId,version:ticket.version,status:'OPEN'}));assert.equal(ticket.status,'OPEN');assert.equal((await detail(user)).history.length,8)
console.log('PASS compact ticket commands, detail refresh, version conflicts, workflow, scoped filtering and archive/restore')
const manuals=ok(await user.get('/support/manuals'));assert.equal(manuals.length,3);assert.equal(manuals[0].availableAssets.length,3)
const pdf=ok(await user.get('/support/manual-asset',{params:{id:'1',format:'PDF'}}));const pdfBytes=Buffer.from(pdf.base64,'base64');assert.equal(pdf.mime,'application/pdf');assert.equal(pdfBytes.subarray(0,5).toString(),'%PDF-');assert.equal(pdf.sha256,createHash('sha256').update(pdfBytes).digest('hex'))
const page=ok(await user.get('/support/manual-asset',{params:{id:'1',format:'PNG',page:2}}));assert.equal(Buffer.from(page.base64,'base64').subarray(0,8).toString('hex'),'89504e470d0a1a0a');assert.equal(page.page,2)
assert.equal((await user.get('/support/manual-asset',{params:{id:'1',format:'PNG',page:'../../'}})).code,'422')
console.log('PASS actual built-in PDF and page-image download, MIME and SHA-256 integrity')
assert.equal((await user.get('/support/platform/ai')).code,'403');let ai=ok(await platform.get('/support/platform/ai'));ai=ok(await platform.post('/support/platform/ai',{version:ai.version,enabled:true,model:'simulation-model',welcomeMsg:'合成欢迎语'}));assert.equal(ai.status,'NOT_CONNECTED');assert.equal(ai.externalCallsEnabled,false)
const payments=ok(await platform.get('/support/platform/payments'));assert.deepEqual(payments.map(x=>x.id),['MOCK','ALIPAY','WECHAT','BANKCARD']);const bank=payments.find(x=>x.id==='BANKCARD');const updated=ok(await platform.post('/support/platform/payments',{version:bank.version,displayEnabled:true,label:'银行卡（合成配置）'},{params:{id:'BANKCARD'}}));assert.equal(updated.status,'NOT_CONNECTED');assert.equal(updated.chargesEnabled,false)
const material=ok(await platform.post('/support/resources/create',{kind:'KNOWLEDGE',title:'HTTP验证资料',content:'合成纯文本内容',audience:'ALL'}));assert.equal(ok(await user.get('/support/resource',{params:{id:material.id}})).content,'合成纯文本内容')
let profile=ok(await user.get('/support/profile'));profile=ok(await user.post('/support/profile',{version:profile.version,nickname:'HTTP合成顾客',avatarUrl:'/avatar.svg',email:'fixture@example.invalid',contactPhone:''}));assert.equal(profile.nickname,'HTTP合成顾客');assert.equal(profile.uid,context.actor.uid);assert.equal(ok(await other.get('/support/profile')).email,'')
console.log('PASS platform-only AI/BANKCARD metadata, persistent material and own-only profile without credential changes')
console.log('PASS live frontend transport integration: all requested checks reached actual Java HTTP')
