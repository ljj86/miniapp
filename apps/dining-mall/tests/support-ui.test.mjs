// Actual Vue app DOM clicks in JSDOM. This verifies reachability/handlers, not browser layout.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {JSDOM} from 'jsdom'
const dom=new JSDOM('<!doctype html><html><body><div id="app"></div></body></html>',{url:'https://mall-test.invalid/#/mall/home',pretendToBeVisual:true})
// Use JSDOM File/FileReader for the real input handler, retaining native Node Blob/URL for previews.
for(const k of ['window','document','location','history','localStorage','sessionStorage','Node','Document','ShadowRoot','Element','HTMLElement','SVGElement','HTMLInputElement','HTMLTextAreaElement','HTMLFormElement','Event','CustomEvent','MouseEvent','KeyboardEvent','MutationObserver','DOMParser','FileReader','File','getComputedStyle'])Object.defineProperty(globalThis,k,{value:k==='getComputedStyle'?dom.window[k].bind(dom.window):dom.window[k],configurable:true})
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true})
globalThis.requestAnimationFrame=dom.window.requestAnimationFrame.bind(dom.window);globalThis.cancelAnimationFrame=dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver=class{observe(){}unobserve(){}disconnect(){}}
dom.window.matchMedia=()=>({matches:false,addListener(){},removeListener(){},addEventListener(){},removeEventListener(){}})
Element.prototype.scrollTo=function(){}
const h=await import('../.test-build/harness.mjs'),api=h.request
const wait=()=>new Promise(r=>setTimeout(r,120))
async function until(predicate,label){const deadline=Date.now()+5000;while(Date.now()<deadline){await h.nextTick();if(predicate())return;await new Promise(r=>setTimeout(r,25))}throw Error('Timed out: '+label+'; DOM: '+document.body.textContent.slice(-2500))}
const success=r=>{assert.equal(r.code,'200',r.msg);return r.data}
async function login(role,unitId){const a=success(await api.post('/web/login',{username:'111',password:'111',role,...(unitId?{unitId}:{})}));sessionStorage.setItem('account',JSON.stringify(a));return a}
async function route(path){await h.router.push(path);await wait()}
async function click(text,root=document){await h.nextTick();const all=[...root.querySelectorAll('button,[role="tab"]')];const target=all.find(e=>e.textContent.trim()===text);assert.ok(target,'missing visible action '+text);assert.ok(!target.disabled,'disabled action '+text);target.click();await wait()}
function input(selector,value,root=document){const el=root.querySelector(selector)||root.querySelector(selector.replace(/ textarea$/,''));assert.ok(el,'missing input '+selector);el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return el}
const app=await h.mount()
async function select(selector){await h.nextTick();const el=document.querySelector(selector);assert.ok(el,'missing '+selector);assert.ok(!el.disabled,'disabled '+selector);el.click();await wait()}
try{
 await login('ROLE_ADMIN');success(await api.post('/unit',{nickname:'隔离测试第二店',username:'demo-shop-2',avatarUrl:'/avatar.svg'}));await login('ROLE_USER');const financeBefore=JSON.stringify(success(await api.get('/credit/overview')).account)
 await route('/mall/shop?id=1');await click('联系本店客服');await wait();assert.equal(h.router.currentRoute.value.path,'/mall/supportChat');const sessionId=h.router.currentRoute.value.query.id
 input('[aria-label="给本店发消息"]','请问20元套餐可以少辣吗？');document.querySelector('[data-testid="customer-support-composer"]').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait();assert.ok(document.querySelector('[data-testid="customer-support-messages"]').textContent.includes('可以少辣'))
 // Real FileReader + v7 multi-file upload handlers. Pending rows and stored messages use opaque metadata.
 const imageBytes=fs.readFileSync('public/brand/favicon-v5.png'),textBytes=Buffer.from('DOM local attachment: synthetic plain text\n仅测试资料','utf8')
 const files=[new File([imageBytes],'test.png',{type:'image/png'}),new File([textBytes],'synthetic.txt',{type:'text/plain'})]
 const upload=document.querySelector('[data-testid="customer-support-composer"] [data-testid="service-upload-input"]');assert.ok(upload,'missing v7 service-upload-input');assert.ok(upload.multiple)
 Object.defineProperty(upload,'files',{value:files,configurable:true});upload.dispatchEvent(new Event('change',{bubbles:true}))
 await until(()=>document.querySelectorAll('[data-testid="customer-support-composer"] .upload-file-row').length===2&&!upload.disabled,'two uploaded attachment metadata rows')
 assert.ok(document.querySelector('[data-testid="customer-support-composer"]').textContent.includes('test.png'));assert.ok(document.querySelector('[data-testid="customer-support-composer"]').textContent.includes('synthetic.txt'))
 document.querySelector('[data-testid="customer-support-composer"]').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))
 await until(()=>document.querySelectorAll('[data-testid="customer-support-messages"] [data-testid="service-attachment"]').length===2,'both opaque attachments in customer message')
 await until(()=>document.querySelector('[data-testid="customer-support-messages"] .service-attachment img')?.src.startsWith('blob:'),'native Blob image preview')
 assert.equal(document.querySelectorAll('[data-testid="customer-support-composer"] .upload-file-row').length,0)
 const messageFiles=success(await api.get('/support/session',{params:{id:sessionId}})).messages.at(-1).attachments
 assert.equal(messageFiles.length,2);assert.deepEqual(messageFiles.map(a=>a.mime),['image/png','text/plain'])
 for(const file of messageFiles){assert.equal(typeof file.id,'string');assert.ok(/^\d+$/.test(file.id));assert.equal(file.securityStatus,'UNSCANNED_SIMULATION');assert.ok(!('base64' in file));assert.ok(!('dataUrl' in file));assert.ok(!('url' in file))}
 assert.equal(Buffer.from(await (await fetch(document.querySelector('.support-message .service-attachment img').src)).arrayBuffer()).equals(imageBytes),true)
 const textDownload=success(await api.get('/support/attachments/download',{params:{id:messageFiles[1].id}}));assert.equal(textDownload.mime,'text/plain');assert.equal(Buffer.from(textDownload.base64,'base64').equals(textBytes),true)
 await route('/login');await click('商家');input('[aria-label="商家体验店铺"]','1');document.querySelector('.entry-content form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait();assert.equal(JSON.parse(sessionStorage.getItem('account')).role,'ROLE_UNIT');await route('/merchant/support');await select('[data-testid="support-session-row"]');assert.ok(document.querySelector('[data-testid="support-message-list"]').textContent.includes('可以少辣'));assert.equal(document.querySelectorAll('[data-testid="support-message-list"] [data-testid="service-attachment"]').length,2);assert.equal(Buffer.from(success(await api.get('/support/attachments/download',{params:{id:messageFiles[1].id}})).base64,'base64').equals(textBytes),true);input('[data-testid="support-message-input"] textarea','本店收到，可以少辣（本机回复）');await select('[data-testid="support-send-message"]');assert.ok(document.querySelector('[data-testid="support-message-list"]').textContent.includes('本店收到'))
 await route('/login');await login('ROLE_USER');await route('/mall/support');assert.ok(success(await api.get('/support/overview')).counts.unreadMessages>=1);await route('/mall/supportChat?id='+sessionId);assert.ok(document.querySelector('[data-testid="customer-support-messages"]').textContent.includes('本店收到'));assert.equal(success(await api.get('/support/overview')).counts.unreadMessages,0)
 await route('/mall/shop?id=1');await click('给本店留言');assert.equal(h.router.currentRoute.value.path,'/mall/supportNew');input('[aria-label="留言标题"]','DOM 留言：套餐建议');input('[aria-label="留言内容"]','希望能提供少辣选项，先沟通不改订单金额');document.querySelector('[data-testid="customer-ticket-form"]').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait();assert.equal(h.router.currentRoute.value.path,'/mall/supportTicket');const ticketId=h.router.currentRoute.value.query.id;assert.ok(document.querySelector('.support-ticket-state').textContent.includes('待处理'))
 await route('/login');await login('ROLE_UNIT',1);await route('/merchant/support');await select('[data-testid="support-tickets-tab"]');await select('[data-testid="support-open-ticket"]');await select('[data-testid="support-advance-ticket"]');assert.equal(document.querySelector('[data-testid="support-ticket-current-status"]').textContent,'处理中');input('[data-testid="support-ticket-reply-input"] textarea','本店已记下建议，可在订单备注说明');await select('[data-testid="support-reply-ticket"]');await select('[data-testid="support-advance-ticket"]');assert.equal(document.querySelector('[data-testid="support-ticket-current-status"]').textContent,'已解决')
 await route('/login');await login('ROLE_USER');await route('/mall/supportTicket?id='+ticketId);assert.ok(document.querySelector('.support-ticket-reply').textContent.includes('本店已记下'));await click('关闭留言');assert.ok(document.querySelector('.support-ticket-state').textContent.includes('已关闭'));await click('重新打开留言');assert.ok(document.querySelector('.support-ticket-state').textContent.includes('待处理'))
 await route('/login');await click('商家');input('[aria-label="商家体验店铺"]','2');document.querySelector('.entry-content form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait();assert.equal(JSON.parse(sessionStorage.getItem('account')).id,2);await route('/merchant/support');assert.equal(document.querySelectorAll('[data-testid="support-session-row"]').length,0);assert.equal(success(await api.get('/support/overview')).tickets.length,0);assert.equal((await api.get('/support/session',{params:{id:sessionId}})).code,'400');for(const file of messageFiles)assert.notEqual((await api.get('/support/attachments/download',{params:{id:file.id}})).code,'200','store2 must not read another shop attachment')
 await route('/login');await login('ROLE_ADMIN');await route('/platform/support');await select('[data-testid="support-tickets-tab"]');await select('[data-testid="support-open-ticket"]');input('[data-testid="support-ticket-reply-input"] textarea','平台补充：留言已转本店跟进');await select('[data-testid="support-reply-ticket"]')
 await route('/login');await login('ROLE_USER');await route('/mall/supportTicket?id='+ticketId);assert.ok(document.querySelector('.support-mobile').textContent.includes('平台补充'));assert.equal(JSON.stringify(success(await api.get('/credit/overview')).account),financeBefore)
 // Order detail enters the order's own shop conversation and retains display context without auto-sending.
 {
  const ordersBefore=success(await api.get('/orders/front/page',{params:{pageSize:100}}))
  const order=ordersBefore.records.find(row=>String(row.id)==='3');assert.ok(order,'order 3 fixture must exist')
  const messagesBefore=success(await api.get('/support/session',{params:{id:sessionId}})).messages.length
  await route('/mall/orderDetail?id='+order.id)
  const navigations=[],stopCapture=h.router.afterEach(to=>navigations.push({path:to.path,query:{...to.query}}))
  await click('联系本店客服');await until(()=>h.router.currentRoute.value.path==='/mall/supportChat'&&document.querySelector('[data-testid="customer-support-composer"]'),'order contact opens chat');stopCapture()
  const entry=navigations.find(row=>row.path==='/mall/support');assert.ok(entry,'order button must use the scoped support entrypoint')
  assert.equal(String(entry.query.unitId),String(order.unitId));assert.equal(String(entry.query.orderId),String(order.id))
  const orderSessionId=h.router.currentRoute.value.query.id
  assert.equal(String(h.router.currentRoute.value.query.orderId),String(order.id),'chat keeps order display context')
  const opened=success(await api.get('/support/session',{params:{id:orderSessionId}}))
  assert.equal(String(opened.session.unitId),String(order.unitId),'chat must belong to the order shop')
  assert.equal(orderSessionId,sessionId,'existing shop session is reused')
  assert.equal(opened.messages.length,messagesBefore,'opening order chat must not auto-send a message')
  const context=document.querySelector('[data-testid="chat-order-context"]');assert.ok(context);assert.ok(context.textContent.includes(order.no));assert.ok(context.textContent.includes(order.name))
  const orderQuestion='订单入口验证：请问这份餐可以分开放吗？'
  input('[aria-label="给本店发消息"]',orderQuestion);document.querySelector('[data-testid="customer-support-composer"]').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))
  await until(()=>document.querySelector('[data-testid="customer-support-messages"]').textContent.includes(orderQuestion),'customer sends order question')
  await route('/login');await login('ROLE_UNIT',Number(order.unitId));await route('/merchant/support')
  await until(()=>document.querySelector('[data-session-id="'+orderSessionId+'"]'),'order shop sees the conversation')
  await select('[data-session-id="'+orderSessionId+'"]');assert.ok(document.querySelector('[data-testid="support-message-list"]').textContent.includes(orderQuestion))
  const orderReply='订单入口商家回复：本店可以分开放'
  input('[data-testid="support-message-input"] textarea',orderReply);await select('[data-testid="support-send-message"]')
  await until(()=>document.querySelector('[data-testid="support-message-list"]').textContent.includes(orderReply),'merchant sends order reply')
  await route('/login');await login('ROLE_USER');await route('/mall/support')
  assert.ok(success(await api.get('/support/overview')).sessions.find(row=>String(row.id)===String(orderSessionId)).unread>0,'customer has an unread merchant reply')
  const unreadRow=[...document.querySelectorAll('.support-session')].find(row=>row.textContent.includes(orderReply));assert.ok(unreadRow?.querySelector('b'),'customer UI displays an unread badge')
  unreadRow.click();await until(()=>h.router.currentRoute.value.path==='/mall/supportChat'&&document.querySelector('[data-testid="customer-support-messages"]')?.textContent.includes(orderReply),'customer reads merchant reply')
  await until(()=>!document.querySelector('.support-empty'),'chat messages loaded')
  assert.equal(success(await api.get('/support/overview')).sessions.find(row=>String(row.id)===String(orderSessionId)).unread,0,'opening reply marks it read')
  await route('/mall/support');assert.ok(![...document.querySelectorAll('.support-session')].find(row=>row.textContent.includes(orderReply))?.querySelector('b'),'unread badge clears after reading')
  assert.deepEqual(success(await api.get('/orders/front/page',{params:{pageSize:100}})),ordersBefore,'support chat must not alter order or payment data')
  assert.equal(JSON.stringify(success(await api.get('/credit/overview')).account),financeBefore,'chat leaves credit unchanged')
  await route('/mall/orderDetail?id='+order.id);await click('向本店咨询此订单');assert.equal(h.router.currentRoute.value.path,'/mall/supportNew');assert.equal(document.querySelector('[aria-label="关联订单"]').value,String(order.id));assert.equal(document.querySelector('[aria-label="留言店铺"]').value,String(order.unitId))
  console.log('PASS actual Vue order-detail contact button → correct shop session + order display context, no auto-send → customer question → merchant workspace reads/replies → customer unread badge/read/cleared badge; unchanged orders and credit; separate order-linked ticket action preserved')
 }
 for(const path of ['/mall/support','/mall/supportChat?id='+sessionId,'/mall/supportTickets','/mall/supportNew','/mall/supportTicket?id='+ticketId,'/front/service','/front/message']){await route(path);assert.ok(document.querySelector('.native-device'),path+' must stay portrait');assert.ok(document.querySelector('.native-device .native-app'),path+' must retain portrait app container');assert.ok(!document.querySelector('.admin-layout,.back-container'),path+' must not enter desktop')}
 console.log('PASS actual Vue DOM: local111 shop→bound chat→text + opaque PNG/TXT multi-upload→native Blob image bytes→merchant login/shop selection→reply→customer unread/read; ticket→merchant progress/reply/resolve→customer close/reopen→platform reply; store2 session/ticket/attachment isolation; unchanged credit and portrait routes')
 console.log('Scope: local JSDOM Vue handlers + real FileReader/local adapter attachment storage, native Blob bytes, and portrait DOM containers; not browser pixels/9:16 dimensions, real Java HTTP, external AI, video playback, or production backend')
}finally{app.unmount();dom.window.close()}
