import { supportResourceQuery, supportResourceAction } from './local-service-resources'
import { HELP_BUILTINS, attachManualMetadata, localManualAsset } from './help-builtins'
import { isBackendMode, backendRequest, useDemoMode, serviceConnection } from './service-mode'
import { serviceKey } from './service-files'
import { supportQuery, supportAction } from './support-service'
import { feedbackOrders, reviewInfo, publicReviews, submitReview, replyReview, afterSalesList, createAfterSales, merchantAfterSales } from './feedback-service'
import { creditOverview, creditAction, createCreditRequests, expireCredit } from './credit-service'
import { sanitizeHTML } from './sanitize'
import seed from './seed.json'

const KEY = 'mall-ui-data-v2'
const clone = value => JSON.parse(JSON.stringify(value))
let db
try { db=JSON.parse(localStorage.getItem(KEY)) || clone(seed) } catch { db=clone(seed) }
// One-time migration respects subsequent user edits and deletions.
function migrateFixtures(target){target.uiMigrations=target.uiMigrations||{};if(!target.uiMigrations.creditFixture){if(!target.goods.some(g=>g.id===700001))target.goods.unshift(clone(seed.goods.find(g=>g.id===700001)));target.uiMigrations.creditFixture=true}}
migrateFixtures(db)
const ok = data => ({code:'200',data:clone(data ?? null),msg:'本机示例数据'})
const fail = msg => ({code:'400',data:null,msg})
const account = () => {try{return JSON.parse(sessionStorage.getItem('account')||'{}')}catch{return {}}}
const entityRole = role => role==='ROLE_ADMIN'?'admin':role==='ROLE_UNIT'?'unit':'user'
const persist = () => localStorage.setItem(KEY,JSON.stringify(db))
const nextId = name => Math.max(0,...db[name].map(r=>Number(r.id)||0))+1
const find = (name,id) => db[name]?.find(r=>String(r.id)===String(id))
const activeGoods = () => db.goods.filter(g=>g.status!==false&&g.status!==0&&g.status!=='下架'&&g.status!=='停用')
const scopedGoods = () => account().role==='ROLE_UNIT'?db.goods.filter(g=>g.unitId===account().id):db.goods
function enrich(name,row) {
  const r=clone(row)
  if(name==='orders'){Object.assign(r,reviewInfo(db,row,account()));const cases=[...(db.afterSales||[]).filter(f=>f.retailOrderId===row.id),...(db.credit?.refunds||[]).filter(f=>f.orderId===row.creditOrderId)];r.afterSalesCount=cases.length;r.afterSalesStatuses=cases.map(f=>f.status)}
  if(name==='goods') r.isCollected=db.collect.some(c=>String(c.itemId)===String(r.id)&&c.userId===(account().id||1))
  if(name==='cart') {
    const g=find('goods',r.goodsId)
    Object.assign(r,{goodsName:g?.name||'餐品已移除',goodsImg:g?.img||'/avatar.svg',goodsInfo:g?.info||'此餐品不可结算，请从购物车移除',goodsPrice:g?.price||0,goodsInventory:g?.inventory||0,unitId:g?.unitId||r.unitId,unavailable:!g||g.status===false||g.status===0})
  }
  if(name==='collect') {const g=find('goods',r.itemId)||{};Object.assign(r,{name:g.name,img:g.img,price:g.price,goodsName:g.name,goodsImg:g.img,goodsPrice:g.price})}
  return r
}
function rows(name,path,params={}) {
  let list=(name==='goods'&&/front|unit|recommend/.test(path)?activeGoods():db[name]||[]).map(r=>enrich(name,r))
  const a=account()
  if(name==='goods'&&path.includes('/collect/'))list=list.filter(g=>db.collect.some(c=>c.itemId===g.id&&c.userId===(a.id||1)))
  if(a.role==='ROLE_UNIT'&&['goods','orders','cart'].includes(name)&&!path.includes('/front'))list=list.filter(r=>r.unitId===a.id)
  if(['cart','collect','address'].includes(name)&&a.role!=='ROLE_ADMIN')list=list.filter(r=>r.userId===(a.id||1))
  if(name==='orders'&&path.includes('/front'))list=list.filter(r=>r.userId===(a.id||1))
  if(params.keyword)list=list.filter(r=>[r.name,r.nickname,r.username,r.no,r.goodsName].filter(Boolean).some(s=>String(s).toLowerCase().includes(String(params.keyword).toLowerCase())))
  for(const k of ['typeId','unitId','userId'])if(params[k]!==undefined&&params[k]!=='')list=list.filter(r=>String(r[k])===String(params[k]))
  if(params.activeTab&&params.activeTab!=='所有订单')list=list.filter(r=>r.status===params.activeTab)
  if(params.sortBy==='sales')list.sort((a,b)=>(b.sales||0)-(a.sales||0))
  if(params.sortBy==='new')list.sort((a,b)=>String(b.date).localeCompare(String(a.date)))
  return list
}
function validateItem(item,addressId) {
  const g=find('goods',item.goodsId),address=find('address',addressId)
  if(!g||g.status===false||g.status===0)throw new Error('餐品已下架或移除，请刷新购物车')
  if(!address)throw new Error('请选择有效的示例地址')
  if(!Number.isFinite(Number(g.price))||Number(g.price)<=0||!Number.isSafeInteger(Math.round(Number(g.price)*100)))throw new Error('商品价格必须是有效正金额')
  const num=Number(item.num)
  if(!Number.isInteger(num)||num<1||num>Number(g.inventory))throw new Error('餐品数量必须为1至库存数量之间的整数')
  return {g,address,num}
}
function makeOrder(item,addressId,id) {
  const {g,address,num}=validateItem(item,addressId)
  const a=account()
  return {id,no:`DEMO-${Date.now()}-${id}`,time:new Date().toLocaleString('zh-CN'),name:g.name,img:g.img,goodsId:g.id,userId:a.role==='ROLE_USER'?a.id:Number(item.userId)||1,unitId:g.unitId,num,price:Math.round(g.price*num*100)/100,userName:address.name,userAddress:address.address,userPhone:address.phone,remark:String(item.remark||''),paymentMode:item.paymentMode==='later'?'later':'now',rate:0,comment:'',reply:'',status:'待支付'}
}
async function runTransaction(method,path,data,config={}) {
  try { const latest=localStorage.getItem(KEY); if(latest)db=JSON.parse(latest);migrateFixtures(db) } catch {}
  const snapshot=clone(db)
  try {
    if(db.credit){expireCredit(db);persist()}
    const params=config.params||{},a=account(),parts=path.split('/').filter(Boolean),name=parts[0]
    if(path.startsWith('/support/')){
      const resource=parts.slice(1).join('/'),resourceDomain=['resources','resource','manuals','manual','updates','profile'].includes(resource)||resource.startsWith('admin/')||resource.startsWith('resources/')||resource.startsWith('platform/')
      if(resource==='manual-asset'){supportResourceQuery(db,a,'manual',{id:String(params.id)},HELP_BUILTINS);return ok(await localManualAsset(params))}
      const result=resourceDomain?(method==='get'?supportResourceQuery(db,a,resource,params,HELP_BUILTINS):supportResourceAction(db,a,resource,data||{},params,HELP_BUILTINS)):(method==='get'?supportQuery(db,a,resource,params):supportAction(db,a,resource,data||{}))
      if(method!=='get')persist();return ok(resourceDomain?attachManualMetadata(result):result)
    }
    if(path==='/credit/overview'){const overview=creditOverview(db,a);persist();return ok(overview)}
    if(method==='post'&&path.startsWith('/credit/')){const result=creditAction(db,a,parts[1],data||{});persist();return ok(result)}
    if(path==='/feedback/orders'){return ok(feedbackOrders(db,a,params))}
    if(path==='/reviews'&&method==='get'){let result=publicReviews(db);if(a.role==='ROLE_UNIT')result=result.filter(r=>Number(r.unitId)===Number(a.id));else if(a.role==='ROLE_USER')result=result.filter(r=>Number(r.userId)===Number(a.id));else if(a.role!=='ROLE_ADMIN')return fail('请先登录');return ok(result)}
    if(path==='/reviews'&&method==='post'){const result=submitReview(db,a,data);persist();return ok(result)}
    if(path==='/reviews/reply'&&method==='post'){const result=replyReview(db,a,data);persist();return ok(result)}
    if(path.startsWith('/reviews/merchant/')&&method==='get')return ok(publicReviews(db,{unitId:parts[2]}))
    if(path.startsWith('/reviews/product/')&&method==='get')return ok(publicReviews(db,{goodsId:parts[2]}))
    if(path==='/after-sales'&&method==='get'){const result=afterSalesList(db,a);persist();return ok(result)}
    if(path==='/after-sales'&&method==='post'){const result=createAfterSales(db,a,data);persist();return ok(result)}
    if(path==='/after-sales/merchant-response'&&method==='post'){const result=merchantAfterSales(db,a,data);persist();return ok(result)}
    if(path.startsWith('/after-sales/')&&method==='get'){const result=afterSalesList(db,a).find(r=>r.id===parts[1]);return result?ok(result):fail('售后单不存在或无权访问')}
    if(path==='/web/login') {
      if(data.username!=='111'||data.password!=='111')return fail('所有体验身份的账号和密码均为 111')
      if(!['ROLE_USER','ROLE_UNIT','ROLE_ADMIN'].includes(data.role))return fail('请选择身份')
      const profile=data.role==='ROLE_UNIT'&&data.unitId!==undefined?db.unit.find(u=>Number(u.id)===Number(data.unitId)):db[entityRole(data.role)][0]
      if(!profile)return fail('所选示例店铺不存在，请重新选择')
      return ok({...profile,role:data.role,username:'111'})
    }
    if(path==='/web/userInfo')return ok({...find(entityRole(a.role),a.id),role:a.role})
    if(path==='/web/password'||path==='/web/register')return fail('此版本无需注册或改密，请选择身份并使用 111 / 111')
    if(path==='/goods/recommend')return ok(activeGoods().slice(0,4))
    if(path==='/type/front')return ok(db.type.filter(t=>t.status!==false&&t.status!==0).slice(0,7))
    if(path==='/type/praise')return ok(db.type.filter(t=>activeGoods().some(g=>g.typeId===t.id)).map(t=>({...t,goodsList:activeGoods().filter(g=>g.typeId===t.id).slice(0,4)})))
    if(path.startsWith('/orders/goodComment/'))return ok(publicReviews(db,{goodsId:parts[2]}))
    if(path==='/echarts/count1')return ok(db.type.map(t=>({name:t.name,value:scopedGoods().filter(g=>g.typeId===t.id).reduce((sum,g)=>sum+(g.sales||0),0)})))
    if(path==='/echarts/count2')return ok(scopedGoods().map(g=>({name:g.name,value:Math.round(g.price*(g.sales||0))})).sort((a,b)=>b.value-a.value).slice(0,10))
    if(name==='orders'&&['pay','back','cancel','delivery','ok','no','send'].includes(parts[1])) {
      if(parts[1]==='pay')return fail('支付未接入：不会扣款，也不会生成支付成功状态')
      const order=find('orders',parts[2]);if(!order)return fail('订单不存在')
      if(order.creditOrderId){if(parts[1]!=='cancel')return fail('先吃后付订单请在专用工作台处理');const result=creditAction(db,a,'cancel',{orderId:order.creditOrderId});persist();return ok(result)}
      if(order.creditRequestId&&parts[1]==='cancel'){const result=creditAction(db,a,'cancel-request',{requestId:order.creditRequestId});persist();return ok(result)}
      const transitions={back:{from:['待评价','已完成'],to:'待退款'},cancel:{from:['待支付'],to:'已取消'},delivery:{from:['待收货'],to:'待评价'},ok:{from:['待退款'],to:'已退款'},no:{from:['待退款'],to:'待评价'},send:{from:['待发货'],to:'待收货'}}
      const rule=transitions[parts[1]]
      if(order.status!==rule.to&&!rule.from.includes(order.status))return fail('当前订单状态不支持此操作')
      if(parts[1]==='back')order.beforeAfterSalesStatus=order.status
      order.status=parts[1]==='no'?(order.comment?'已完成':'待评价'):rule.to
      if(parts[1]==='delivery')order.fulfilledAt=new Date().toISOString()
      persist();return ok(order)
    }
    if(method==='post'&&path.startsWith('/orders/fromCart/')) {
      if(!Array.isArray(data)||!data.length)return fail('请选择餐品')
      const ids=new Set(data.map(x=>Number(x.id)))
      if(ids.size!==data.length)return fail('购物车条目不能重复')
      for(const item of data)if(!find('cart',item.id)||Number(find('cart',item.id).goodsId)!==Number(item.goodsId))return fail('购物车已变化，请刷新后重试')
      const startId=nextId('orders')
      // Validate and construct every line before mutating either collection.
      const made=data.map((item,index)=>makeOrder(item,parts[2],startId+index))
      db.orders.unshift(...made)
      if(data.some(item=>item.paymentMode==='later')){if(!data.every(item=>item.paymentMode==='later'))throw new Error('同一次结算方式必须一致');createCreditRequests(db,a,made)}
      db.cart=db.cart.filter(c=>!ids.has(c.id))
      persist();return ok(made)
    }
    if(!db[name])return fail('此界面操作尚不支持')
    if(method==='get') {
      if(parts.at(-1)==='page'){const list=rows(name,path,params),size=Number(params.pageSize)||10,start=((Number(params.pageNum)||1)-1)*size;return ok({records:list.slice(start,start+size),total:list.length})}
      if(parts.length===2){const row=find(name,parts[1]);return row?ok(enrich(name,row)):fail('记录不存在')}
      return ok(rows(name,path,params))
    }
    if(method==='delete') {
      if(name==='orders'&&(find(name,parts[1])?.creditOrderId||find(name,parts[1])?.creditRequestId))return fail('先吃后付记录保留业务历史，请使用取消流程')
      db[name]=db[name].filter(r=>String(name==='collect'&&a.role!=='ROLE_ADMIN'?r.itemId:r.id)!==parts[1]);persist();return ok(true)
    }
    if(method==='post') {
      if(path.endsWith('/del/batch')){if(name==='orders'&&db.orders.some(o=>data.includes(o.id)&&(o.creditOrderId||o.creditRequestId)))return fail('先吃后付记录不能批量删除');db[name]=db[name].filter(r=>!data.includes(r.id));persist();return ok(true)}
      const row=clone(data||{})
      for(const field of ['content','comment','reply'])if(row[field])row[field]=sanitizeHTML(row[field])
      delete row.password;delete row.token;delete row.confirmPassword
      if(name==='cart') {
        row.goodsId=Number(row.goodsId);row.num=Number(row.num)
        const g=find('goods',row.goodsId)
        if(!g||g.status===false||g.status===0)return fail('餐品已下架或移除')
        const existing=row.id?find('cart',row.id):db.cart.find(c=>c.goodsId===row.goodsId&&c.userId===(a.id||1))
        const count=existing&&!row.id?existing.num+row.num:row.num
        if(!Number.isInteger(count)||count<1||count>g.inventory)return fail('数量超过示例库存或格式不正确')
        row.num=count;row.unitId=g.unitId;row.userId=a.role==='ROLE_ADMIN'?Number(row.userId)||1:a.id||1
        if(existing){Object.assign(existing,row);persist();return ok(existing)}
      }
      if(name==='collect'&&!row.id) {
        const index=db.collect.findIndex(c=>String(c.itemId)===String(row.itemId)&&c.userId===(a.id||1))
        if(index>=0){db.collect.splice(index,1);persist();return {code:'605',data:null,msg:'已取消收藏'}}
        row.itemId=Number(row.itemId);row.userId=a.id||1
      }
      if(name==='address') {
        for(const key of ['name','address','phone'])if(!String(row[key]||'').trim())return fail('请填写完整地址信息')
        if(!row.id)row.userId=a.id||1
      }
      if(name==='orders'&&!row.id){const order=makeOrder(row,row.addressId,nextId('orders'));db.orders.unshift(order);persist();return ok(order)}
      if(name==='goods') {
        row.price=Number(row.price);row.inventory=Number(row.inventory)||0;row.sales=Number(row.sales)||0
        if(!Number.isFinite(row.price)||row.price<=0||!Number.isSafeInteger(Math.round(row.price*100))||Math.abs(row.price*100-Math.round(row.price*100))>0.000001)return fail('价格必须大于0，最多保留两位小数')
        if(!Number.isSafeInteger(row.inventory)||row.inventory<0||!Number.isSafeInteger(row.sales)||row.sales<0)return fail('库存和销量必须为非负整数')
        row.version=Number(find('goods',row.id)?.version||1)+1
        row.unitId=a.role==='ROLE_UNIT'?a.id:Number(row.unitId)||db.unit[0]?.id
        row.typeId=Number(row.typeId)||db.type[0]?.id;row.status=row.status===undefined?true:row.status
        row.date=row.date||new Date().toISOString().slice(0,10);row.img=row.img||db.goods[0]?.img||'/avatar.svg';row.unit=row.unit||'份'
      }
      const existing=find(name,row.id)
      if(name==='orders'&&existing&&(existing.creditOrderId||existing.creditRequestId)) {
        const frozenFields=['price','unitId','userId','goodsId','num','no','name','img','time','userName','userAddress','userPhone','remark','paymentMode','creditOrderId','creditRequestId','creditStatus']
        for(const field of frozenFields)if(Object.prototype.hasOwnProperty.call(row,field)&&JSON.stringify(row[field])!==JSON.stringify(existing[field]))return fail('信用订单金额、归属和关联快照不可在普通订单表修改')
        if(a.role==='ROLE_USER')submitReview(db,a,row)
        else if(a.role==='ROLE_UNIT')replyReview(db,a,row)
        else return fail('信用订单的业务变化请使用先吃后付工作台')
        persist();return ok(existing)
      }
      if(name==='orders'&&existing&&a.role==='ROLE_USER'&&row.comment!==undefined){const result=submitReview(db,a,row);persist();return ok(result)}
      if(name==='orders'&&existing&&a.role==='ROLE_UNIT'&&row.reply!==undefined&&existing.comment){const result=replyReview(db,a,row);persist();return ok(result)}
      if(existing)Object.assign(existing,row);else{row.id=nextId(name);db[name].push(row)}
      persist();return ok(row)
    }
    return fail('不支持此操作')
  } catch(error) {
    db=snapshot
    return fail(error.message||'本机存储空间不足，请清理示例图片或重置数据')
  }
}
function run(method,path,data,config={}) {
  if(path.includes('?')){const [clean,query]=path.split('?');path=clean;config={...config,params:{...Object.fromEntries(new URLSearchParams(query)),...(config.params||{})}}}
  if(method==='post'&&path.startsWith('/support/'))data={...(data||{}),clientKey:data?.clientKey||serviceKey('ACTION')}
  if(isBackendMode())return backendRequest(method,path,data,config)
  // Recheck the connection after the lock wait, including switches away and back to demo.
  const identity=a=>(a.role||'')+':'+String(a.id??''),origin=identity(account()),revision=serviceConnection.revision
  const execute=()=>{
    if(isBackendMode()||serviceConnection.revision!==revision)return Promise.resolve(fail('服务模式或会话已切换，请在当前模式下重新操作'))
    return identity(account())===origin?runTransaction(method,path,data,config):Promise.resolve(fail('体验身份已切换，请在当前身份下重新操作'))
  }
  if(typeof navigator!=='undefined'&&navigator.locks)return navigator.locks.request('mall-demo-atomic-state',execute)
  return execute()
}
export const resetDemo = () => {if(isBackendMode())throw new Error('后端模式不支持重置服务端数据，请明确切回本机体验');db=clone(seed);persist();sessionStorage.removeItem('account');localStorage.removeItem('mall-ui-bills');localStorage.removeItem('mall-ui-address');location.hash='#/mall/home';location.reload()}
export const demoStores = () => rows('unit','/unit')
export default {get:(url,config)=>run('get',url,null,config),post:(url,data,config)=>run('post',url,data,config),delete:(url,config)=>run('delete',url,null,config)}
