const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict')
let account={},guard
const code=fs.readFileSync('src/router/index.js','utf8').replace(/^import.*$/mg,'').replace('import.meta.env.BASE_URL',"'/'").replace('export default router','')
const ctx={projectName:'UI test',localStorage:{getItem:()=>JSON.stringify(account)},createWebHashHistory:()=>({}),createRouter:()=>({beforeEach:fn=>guard=fn,afterEach:()=>{}})}
ctx.sessionStorage=ctx.localStorage;vm.createContext(ctx);vm.runInContext(code,ctx)
function go(role,path){account=role?{id:1,role}:{};let destination='ALLOW';guard({path,matched:[{}]},{},result=>destination=(typeof result==='object'?result.path:result)||'ALLOW');return destination}
assert.equal(go(null,'/merchant/home'),'/login')
assert.equal(go('ROLE_USER','/platform/goods'),'/login')
assert.equal(go('ROLE_USER','/mall/cart'),'ALLOW')
assert.equal(go('ROLE_UNIT','/mall/checkout'),'/login')
assert.equal(go('ROLE_UNIT','/merchant/goods'),'ALLOW')
assert.equal(go('ROLE_UNIT','/merchant/notice'),'/merchant/home')
assert.equal(go('ROLE_UNIT','/platform/orders'),'/merchant/home')
assert.equal(go('ROLE_ADMIN','/platform/notice'),'ALLOW')
assert.equal(go('ROLE_ADMIN','/merchant/goods'),'/platform/home')
assert.equal(go('ROLE_ADMIN','/back/goods'),'/platform/goods')
for(const page of ['orderService','afterSales','afterSale','supportChat','supportTickets','supportNew','supportTicket']){assert.equal(go('ROLE_USER','/mall/'+page),'ALLOW');assert.equal(go(null,'/mall/'+page),'/login');assert.equal(go('ROLE_UNIT','/mall/'+page),'/login')}
for(const page of ['paymentManagement','payWechat','payBank','payAlipay','aiManagement','aiConfiguration','aiSessions']){assert.equal(go('ROLE_ADMIN','/platform/'+page),'ALLOW');assert.equal(go('ROLE_UNIT','/merchant/'+page),'/merchant/home');assert.equal(go('ROLE_USER','/platform/'+page),'/login')}
assert.equal(go('ROLE_UNIT','/merchant/afterSales'),'ALLOW');assert.equal(go('ROLE_ADMIN','/platform/afterSales'),'ALLOW')
assert.equal(go('ROLE_UNIT','/merchant/support'),'ALLOW');assert.equal(go('ROLE_ADMIN','/platform/support'),'ALLOW');assert.equal(go(null,'/front/service'),'/mall/support');assert.equal(go('ROLE_USER','/front/message'),'/mall/supportTickets')
console.log('PASS: 58 customer/merchant/platform route-separation checks including feedback, refunds, payment and AI management')
