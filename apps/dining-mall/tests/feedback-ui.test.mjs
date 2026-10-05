// Actual Vue app DOM clicks in JSDOM. This verifies reachability/handlers, not browser layout.
import assert from 'node:assert/strict'
import {JSDOM} from 'jsdom'
const dom=new JSDOM('<!doctype html><html><body><div id="app"></div></body></html>',{url:'https://mall-test.invalid/#/mall/home',pretendToBeVisual:true})
for(const k of ['window','document','location','history','localStorage','sessionStorage','Node','Element','HTMLElement','SVGElement','HTMLInputElement','HTMLTextAreaElement','HTMLFormElement','Event','CustomEvent','MouseEvent','KeyboardEvent','MutationObserver','DOMParser','getComputedStyle'])Object.defineProperty(globalThis,k,{value:k==='getComputedStyle'?dom.window[k].bind(dom.window):dom.window[k],configurable:true})
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true})
globalThis.requestAnimationFrame=dom.window.requestAnimationFrame.bind(dom.window);globalThis.cancelAnimationFrame=dom.window.cancelAnimationFrame.bind(dom.window)
globalThis.ResizeObserver=class{observe(){}unobserve(){}disconnect(){}}
dom.window.matchMedia=()=>({matches:false,addListener(){},removeListener(){},addEventListener(){},removeEventListener(){}})
Element.prototype.scrollTo=function(){}
const h=await import('../.test-build/harness.mjs'),api=h.request
const wait=()=>new Promise(r=>setTimeout(r,120))
const success=r=>{assert.equal(r.code,'200',r.msg);return r.data}
async function login(role){const a=success(await api.post('/web/login',{username:'111',password:'111',role}));sessionStorage.setItem('account',JSON.stringify(a));return a}
async function route(path){await h.router.push(path);await wait()}
async function click(text,root=document){await h.nextTick();const all=[...root.querySelectorAll('button,[role="tab"]')];const target=all.find(e=>e.textContent.trim()===text);assert.ok(target,'missing visible action '+text);assert.ok(!target.disabled,'disabled action '+text);target.click();await wait()}
function input(selector,value,root=document){const el=root.querySelector(selector);assert.ok(el,'missing input '+selector);el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return el}
const app=await h.mount()
try{
 await login('ROLE_USER');success(await api.post('/cart',{goodsId:700001,num:1,unitId:1}));const cart=(await api.get('/cart')).data.find(x=>x.goodsId===700001)
 success(await api.post('/orders/fromCart/1',[{id:cart.id,goodsId:700001,num:1,paymentMode:'later'}]))
 await login('ROLE_UNIT');let c=success(await api.get('/credit/overview'));const order=success(await api.post('/credit/publish',{requestId:c.requests[0].id}))
 await login('ROLE_USER');c=success(await api.get('/credit/overview'));let o=c.orders.find(x=>x.id===order.id)
 success(await api.post('/credit/confirm',{orderId:o.id,credential:o.confirmationCredential.credential,version:o.version,snapshotHash:o.snapshotHash,agreementAccepted:true}))
 const code=success(await api.post('/credit/fulfillment-code',{orderId:o.id}));await login('ROLE_UNIT');success(await api.post('/credit/redeem',{orderId:o.id,credential:code.credential}))
 await login('ROLE_USER');c=success(await api.get('/credit/overview'));const rep=success(await api.post('/credit/repay',{merchantUid:c.receivables[0].merchantUid,receivableIds:[c.receivables[0].id],amountMinor:800,clientKey:'dom-eight'}));await login('ROLE_ADMIN');success(await api.post('/credit/simulate-result',{personaId:'701',repaymentId:rep.id,reference:rep.reference,merchantUid:rep.merchantUid,amountMinor:800,currency:'CNY',environment:'SIMULATION',providerEventId:'dom-paid',result:'CONFIRMED'}));await login('ROLE_USER');await route('/mall/bill');assert.ok(document.querySelector('.native-device'))
 await click('评价与售后');assert.equal(h.router.currentRoute.value.path,'/mall/orderService')
 await click('去评价');input('[data-testid="review-editor"] textarea','DOM 点击评价 A & B\n口味不错')
 document.querySelector('[data-testid="review-editor"] form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait()
 assert.ok(document.querySelector('.saved-purchase-review').textContent.includes('DOM 点击评价 A & B'))
 assert.equal(success(await api.get('/credit/overview')).account.principalMinor,1200)
 await click('查看餐品与评价');assert.equal(h.router.currentRoute.value.path,'/mall/detail');assert.ok(document.querySelector('.native-app').textContent.includes('DOM 点击评价 A & B'))
 await route('/mall/shop?id=1');assert.ok(document.querySelector('.native-app').textContent.includes('DOM 点击评价 A & B'))
 await route('/mall/deferredOrder?id='+o.id);await click('申请售后 / 退款');input('[aria-label="售后申请金额"]','15');input('[data-testid="after-sales-editor"] textarea','DOM 售后申请：菜品问题')
 document.querySelector('[data-testid="after-sales-editor"] form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait()
 assert.equal(h.router.currentRoute.value.path,'/mall/afterSale');const refundId=h.router.currentRoute.value.query.id
 assert.ok(document.querySelector('.native-app').textContent.includes('待商家反馈'))
 await route('/login');await login('ROLE_UNIT');await route('/merchant/afterSales');await click('查看并处理')
 const dlg=[...document.querySelectorAll('[role="dialog"]')].find(e=>e.textContent.includes('售后详情与处理'));assert.ok(dlg)
 input('textarea','商家已核对，支持申请',dlg);await click('支持申请，提交处理意见',dlg)
 assert.equal(success(await api.get('/after-sales/'+refundId)).merchantDecision,'SUPPORT')
 dlg.querySelector('.el-dialog__headerbtn').click();await wait();await click('店铺评价')
 const row=[...document.querySelectorAll('.el-table__row')].find(e=>e.textContent.includes('DOM 点击评价 A & B'));assert.ok(row);await click('回复评价',row)
 let rd=[...document.querySelectorAll('[role="dialog"]')].find(e=>e.textContent.includes('回复顾客评价'));input('textarea','A & B\n谢谢',rd);await click('保存回复',rd)
 const row2=[...document.querySelectorAll('.el-table__row')].find(e=>e.textContent.includes('DOM 点击评价 A & B'));await click('编辑回复',row2)
 rd=[...document.querySelectorAll('[role="dialog"]')].find(e=>e.textContent.includes('回复顾客评价'));assert.equal(rd.querySelector('textarea').value,'A & B\n谢谢');await click('保存回复',rd)
 await route('/login');await login('ROLE_ADMIN');sessionStorage.setItem('platformDemoPersona','402');await route('/platform/afterSales');await click('查看并处理')
 const pd=[...document.querySelectorAll('[role="dialog"]')].find(e=>e.textContent.includes('售后详情与处理'));input('textarea','独立核实批准部分退款',pd);await click('402独立审核通过',pd)
 assert.equal(success(await api.get('/after-sales/'+refundId)).principalReductionMinor,1200)
 await route('/login');sessionStorage.setItem('platformDemoPersona','701');await route('/platform/afterSales');await click('查看进度');const cashDialog=[...document.querySelectorAll('[role="dialog"]')].find(e=>e.textContent.includes('售后详情与处理'));await click('完成模拟现金退回',cashDialog);assert.equal(success(await api.get('/after-sales/'+refundId)).returnedMinor,300);
 await route('/login');await login('ROLE_USER');await route('/mall/afterSale?id='+refundId)
 assert.ok(document.querySelector('.native-app').textContent.includes('处理完成'));assert.ok(document.querySelector('.native-app').textContent.includes('独立核实批准部分退款'))
 await route('/mall/deferredOrder?id='+o.id);assert.ok(document.querySelector('.saved-purchase-review').textContent.includes('A & B'));assert.ok(document.querySelector('.review-reply').innerHTML.includes('<br>'))
 assert.equal(success(await api.get('/credit/overview')).account.principalMinor,0)
 // Persisted data and route reload: same app remount is unnecessary to prove storage; re-read adapter and revisit all new routes.
 for(const path of ['/mall/orders','/mall/orderDetail?id=3','/mall/afterSales','/mall/afterSale?id='+refundId,'/mall/orderService?creditId='+o.id]){await route(path);assert.ok(document.querySelector('.native-device'),path+' lost portrait shell');assert.ok(!document.querySelector('.back-container'),path+' entered backend')}
 await route('/login');await login('ROLE_ADMIN');await route('/platform/paymentManagement');{const card=document.querySelector('.integration-channel');assert.ok(card);card.click();await wait()}assert.equal(h.router.currentRoute.value.path,'/platform/payWechat');assert.ok(document.querySelector('.integration-page').textContent.includes('未启用'));assert.equal(document.querySelectorAll('.integration-page input').length,0);await route('/platform/aiManagement');assert.ok(document.querySelector('.integration-page').textContent.includes('源码模块已核实'));const aiCards=document.querySelectorAll('.ai-grid .integration-channel');assert.equal(aiCards.length,2);aiCards[0].click();await wait();assert.equal(h.router.currentRoute.value.path,'/platform/aiConfiguration');assert.equal(document.querySelectorAll('.integration-page input').length,0);await route('/platform/aiSessions');assert.ok(document.querySelector('.integration-page').textContent.includes('无法读取实际会话'));
 console.log('PASS actual Vue DOM clicks: credit bill → review form submit → product/store visible; fulfilled detail → partial after-sales form → merchant processing → platform402 approval +701 cash return → customer progress; merchant reply/edit preserves text; customer routes retain native-device shell')
 console.log('Scope: JSDOM verifies rendered DOM and real Vue handlers, not browser pixel layout or live hosting authentication')
}finally{app.unmount();dom.window.close()}
