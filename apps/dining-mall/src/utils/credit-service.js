// Local-only implementation of the source contract SM-01...SM-12.
// The fixture rule is not a real credit approval, loan, payment or security boundary.
export const BASE_RULE = Object.freeze({code:'RV-SIM-TEST-001',quotaMinor:10000,transactionMaxMinor:2000,termDays:30,credentialTtlSeconds:300,reservationTtlSeconds:900,fixtureOnly:true,formalApproval:false})
const copy = value => JSON.parse(JSON.stringify(value))
const ensure = (condition,message) => {if(!condition)throw new Error(message)}
const iso = time => new Date(time).toISOString()
const uid = actor => `y${String(100+Number(actor.id||1)).padStart(8,'0')}`
const mid = actor => `s${String(actor.id||1).padStart(7,'0')}`
const userOf = actor => ({userId:String(100+Number(actor.id||1)),userUid:uid(actor)})
const id = (state,prefix) => `${prefix}-${++state.sequence}`
const integer = (value,message='金额须为正整数分') => {ensure(Number.isSafeInteger(Number(value))&&Number(value)>0,message);return Number(value)}
const actorLabel = (actor,data={}) => actor.role==='ROLE_ADMIN'?String(data.personaId||'701'):actor.role==='ROLE_UNIT'?'201':String(100+Number(actor.id||1))
function stateOf(db) {
  if(!db.credit)db.credit={schemaVersion:1,sequence:0,environment:'SIMULATION',rule:copy(BASE_RULE),requests:[],orders:[],reservations:[],receivables:[],repayments:[],events:[],disputes:[],ruleDrafts:[],resolutionRequests:[],idempotency:{},providerEvents:{},manualFrozen:[]}
  db.credit.resolutionRequests=db.credit.resolutionRequests||[]
  db.credit.refunds=db.credit.refunds||[]
  return db.credit
}
const event=(state,type,details,now,actor)=>state.events.push({id:id(state,'EVT'),type,...details,at:iso(now),actorId:actor||'SYSTEM',environment:'SIMULATION'})
function validateOrderItems(db,order,versions=false){
  integer(order.totalMinor,'订单总金额必须为正整数分')
  let total=0
  for(const item of order.items){
    integer(item.quantity,'数量必须为正整数');integer(item.unitPriceMinor,'单价必须为正整数分');integer(item.subtotalMinor,'小计必须为正整数分')
    ensure(item.subtotalMinor===item.unitPriceMinor*item.quantity,'小计与数量、单价不一致');total+=item.subtotalMinor
    const goods=db.goods.find(g=>g.id===item.productId)
    ensure(goods&&(goods.status===true||goods.status===1),'餐品已下架或删除')
    const priceMinor=Number(goods.price)*100
    ensure(Number.isFinite(priceMinor)&&priceMinor>0&&Number.isSafeInteger(Math.round(priceMinor))&&Math.abs(priceMinor-Math.round(priceMinor))<0.000001,'商品价格不是有效的整数分金额')
    ensure(String(goods.unitId)===String(order.merchantId),'餐品所属商家已变化')
    ensure(Math.round(Number(goods.price)*100)===item.unitPriceMinor,'餐品价格已变化，请重新发起请求')
    if(versions)ensure(Number(goods.version||1)===Number(order.productVersions?.[item.productId]),'餐品版本已变化，请重新读取订单')
  }
  ensure(Number.isSafeInteger(total)&&total===order.totalMinor,'订单总金额不一致')
}
const userOnly=actor=>ensure(actor.role==='ROLE_USER','仅顾客身份可执行此操作')
const merchantOnly=(actor,merchantUid)=>ensure(actor.role==='ROLE_UNIT'&&mid(actor)===merchantUid,'仅该商家的演示经营者可执行此操作')
function persona(actor,data,expected){ensure(actor.role==='ROLE_ADMIN'&&String(data.personaId)===String(expected),'请切换到此操作对应的平台合成人格')}
const byId=(list,value,label)=>{const row=list.find(x=>x.id===String(value));ensure(row,label+'不存在');return row}
function retailState(db,order,status) {
  for(const row of db.orders)if(order.retailOrderIds?.includes(row.id)){row.status=status;row.creditOrderId=order.id;row.creditStatus=order.status}
}
function release(db,state,order,status,now,actor) {
  ensure(order.status!=='FULFILLED','已经核销的订单不能通过取消抹掉应收本金')
  if(['CANCELLED','EXPIRED'].includes(order.status))return order
  const reservation=state.reservations.find(r=>r.orderId===order.id)
  if(reservation?.status==='HELD'){reservation.status=status==='EXPIRED'?'EXPIRED':'RELEASED';event(state,'RESERVATION_RELEASED',{orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,amountMinor:reservation.amountMinor,reason:status},now,actor)}
  order.status=status;order.fulfillmentStatus='VOID';order.version++
  if(order.fulfillmentCredential)order.fulfillmentCredential.revoked=true
  if(order.confirmationCredential)order.confirmationCredential.revoked=true
  retailState(db,order,status==='EXPIRED'?'已过期':'已取消')
  return order
}
export function expireCredit(db,now=Date.now()) {
  const state=stateOf(db)
  for(const order of state.orders){
    if(order.status==='PUBLISHED'&&Date.parse(order.expiresAt)<=now)release(db,state,order,'EXPIRED',now,'SYSTEM')
    const r=state.reservations.find(r=>r.orderId===order.id)
    if(order.status==='CONFIRMED'&&r?.status==='HELD'&&Date.parse(r.expiresAt)<=now)release(db,state,order,'EXPIRED',now,'SYSTEM')
  }
  return state
}
export function creditAccount(db,userUid,now=Date.now()) {
  const state=stateOf(db)
  const reservedMinor=state.reservations.filter(r=>r.userUid===userUid&&r.status==='HELD').reduce((sum,r)=>sum+r.amountMinor,0)
  const receivables=state.receivables.filter(r=>r.userUid===userUid)
  const principalMinor=receivables.reduce((sum,r)=>sum+r.outstandingMinor,0)
  const overdue=receivables.some(r=>r.outstandingMinor>0&&Date.parse(r.dueAt)<=now)
  const disputed=state.disputes.some(d=>d.userUid===userUid&&['OPEN','REVIEWING','APPEALED'].includes(d.status))
  return {userUid,totalMinor:state.rule.quotaMinor,reservedMinor,principalMinor,availableMinor:state.rule.quotaMinor-reservedMinor-principalMinor,blocked:state.manualFrozen.includes(userUid)||overdue||disputed,overdue,disputed,version:state.events.length+1}
}
function credential(state,order,purpose,now) {
  const token=`DEMO-${purpose}-${id(state,'CODE')}`
  const deadline=Math.min(now+order.ruleSnapshot.credentialTtlSeconds*1000,purpose==='FULFILL'?Date.parse(state.reservations.find(r=>r.orderId===order.id).expiresAt):Infinity)
  return {credential:token,purpose,orderId:order.id,version:order.version,expiresAt:iso(deadline),used:false,revoked:false}
}
function checkCredential(value,provided,purpose,now) {
  ensure(value&&value.purpose===purpose&&provided===value.credential,'凭证类型或内容不正确')
  ensure(!value.revoked&&!value.used,'该凭证已使用或作废')
  ensure(Date.parse(value.expiresAt)>now,'该凭证已过期，请重新获取')
}
function scopeOrder(actor,order) {
  ensure(actor.role==='ROLE_ADMIN'||(actor.role==='ROLE_USER'&&uid(actor)===order.userUid)||(actor.role==='ROLE_UNIT'&&mid(actor)===order.merchantUid),'此身份无权访问该订单')
}
function summarizeRepayment(repayment){return copy(repayment)}
export function creditOverview(db,actor,now=Date.now()) {
  const state=expireCredit(db,now)
  ensure(['ROLE_USER','ROLE_UNIT','ROLE_ADMIN'].includes(actor.role),'请先选择体验身份')
  const matches=row=>actor.role==='ROLE_ADMIN'||(actor.role==='ROLE_USER'?row.userUid===uid(actor):row.merchantUid===mid(actor))
  const orders=state.orders.filter(matches).map(o=>{
    const out=copy(o);if(actor.role!=='ROLE_USER'){delete out.confirmationCredential;delete out.fulfillmentCredential;delete out.fulfilledCredential}return out
  })
  const receivables=state.receivables.filter(matches).map(r=>({...copy(r),overdue:r.outstandingMinor>0&&Date.parse(r.dueAt)<=now}))
  const result={environment:'SIMULATION',rule:copy(state.rule),requests:copy(state.requests.filter(matches)),orders,reservations:copy(state.reservations.filter(matches)),receivables,repayments:state.repayments.filter(matches).map(summarizeRepayment),events:copy(state.events.filter(matches).slice(-80).reverse()),disputes:copy(state.disputes.filter(matches)),refunds:copy(state.refunds.filter(matches)),depositStatus:'NOT_APPLICABLE',settlementStatus:'NOT_APPLICABLE',now:iso(now)}
  if(actor.role==='ROLE_USER')result.account=creditAccount(db,uid(actor),now)
  if(actor.role==='ROLE_UNIT')result.merchantSummary={merchantUid:mid(actor),reservedMinor:result.reservations.filter(r=>r.status==='HELD').reduce((s,r)=>s+r.amountMinor,0),principalMinor:receivables.reduce((s,r)=>s+r.outstandingMinor,0),repaidMinor:result.repayments.reduce((s,r)=>s+(r.allocations||[]).reduce((n,a)=>n+a.amountMinor,0),0),pendingRequests:result.requests.filter(r=>r.status==='WAITING_MERCHANT').length}
  const returnedMinor=result.refunds.reduce((sum,r)=>sum+r.returnedMinor,0)
  result.refundSummary={returnedMinor,returnPayableMinor:result.refunds.reduce((sum,r)=>sum+Math.max(0,r.returnPayableMinor-r.returnedMinor),0),principalReductionMinor:result.refunds.reduce((sum,r)=>sum+r.principalReductionMinor,0)}
  if(result.merchantSummary){result.merchantSummary.returnedMinor=returnedMinor;result.merchantSummary.receivedMinor=result.repayments.reduce((sum,p)=>sum+p.receivedMinor,0);result.merchantSummary.unallocatedMinor=result.repayments.reduce((sum,p)=>sum+p.unallocatedMinor,0);result.merchantSummary.netReceivedMinor=result.merchantSummary.receivedMinor-returnedMinor}
  if(actor.role==='ROLE_ADMIN'){
    const userUids=[...new Set([...(db.user||[]).map(u=>`y${String(100+Number(u.id)).padStart(8,'0')}`),...state.orders.map(o=>o.userUid)])]
    result.accounts=userUids.map(user=>creditAccount(db,user,now));result.ruleDrafts=copy(state.ruleDrafts);result.resolutionRequests=copy(state.resolutionRequests)
    result.invariant=result.accounts.every(a=>a.totalMinor===a.reservedMinor+a.principalMinor+a.availableMinor&&a.availableMinor>=0)
  }
  return result
}
export function createCreditRequests(db,actor,retailOrders,now=Date.now()) {
  userOnly(actor)
  const state=expireCredit(db,now),groups=new Map()
  for(const row of retailOrders){const key=String(row.unitId);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(row)}
  for(const row of retailOrders){integer(row.num,'数量必须为正整数');integer(Math.round(Number(row.price)*100),'订单金额必须为正整数分')}
  // Validate all groups before mutation. The customer requests publication, never impersonates a merchant.
  for(const rows of groups.values())ensure(rows.reduce((sum,r)=>sum+Math.round(r.price*100),0)<=state.rule.transactionMaxMinor,`当前原规则演示单笔上限为¥${(state.rule.transactionMaxMinor/100).toFixed(2)}，请使用额度内餐品或普通结算`)
  const made=[]
  for(const [merchantId,rows] of groups){
    const request={id:id(state,'REQ'),...userOf(actor),merchantId,merchantUid:`s${merchantId.padStart(7,'0')}`,storeId:merchantId,retailOrderIds:rows.map(r=>r.id),items:rows.map(r=>({productId:r.goodsId,name:r.name,img:r.img,quantity:r.num,unitPriceMinor:Math.round(r.price/r.num*100),subtotalMinor:Math.round(r.price*100)})),totalMinor:rows.reduce((sum,r)=>sum+Math.round(r.price*100),0),status:'WAITING_MERCHANT',createdAt:iso(now),remark:rows[0].remark||''}
    validateOrderItems(db,request)
    state.requests.push(request);made.push(request)
    for(const row of rows){row.creditRequestId=request.id;row.status='待商家发布';row.paymentMode='later'}
    event(state,'DINING_REQUEST_CREATED',{requestId:request.id,userUid:request.userUid,merchantUid:request.merchantUid,amountMinor:request.totalMinor},now,actorLabel(actor))
  }
  return made
}
function allocate(state,repayment,now,actor){
  let remaining=repayment.receivedMinor
  repayment.allocations=repayment.allocations||[]
  const already=repayment.allocations.reduce((sum,a)=>sum+a.amountMinor,0)
  remaining-=already
  const targets=state.receivables.filter(r=>repayment.receivableIds.includes(r.id)&&r.userUid===repayment.userUid&&r.merchantUid===repayment.merchantUid).sort((a,b)=>a.dueAt.localeCompare(b.dueAt)||a.id.localeCompare(b.id))
  for(const r of targets){const amount=Math.min(remaining,r.outstandingMinor);if(amount<=0)continue;r.outstandingMinor-=amount;r.status=r.outstandingMinor===0?'SETTLED':'PARTIAL';r.version++;repayment.allocations.push({id:id(state,'ALLOC'),receivableId:r.id,merchantUid:r.merchantUid,amountMinor:amount});remaining-=amount;event(state,'PRINCIPAL_REPAID',{repaymentId:repayment.id,receivableId:r.id,orderId:r.orderId,userUid:r.userUid,merchantUid:r.merchantUid,amountMinor:amount},now,actor)}
  repayment.unallocatedMinor=remaining;repayment.status='CONFIRMED';repayment.channelResult='CONFIRMED';repayment.confirmedAt=iso(now);repayment.version++
}
export function creditAction(db,actor,action,data={},now=Date.now()) {
  const state=expireCredit(db,now),who=actorLabel(actor,data)
  if(action==='refresh')return creditOverview(db,actor,now)
  if(action==='publish'){
    const request=byId(state.requests,data.requestId,'用餐请求');merchantOnly(actor,request.merchantUid)
    if(request.orderId)return byId(state.orders,request.orderId,'订单')
    ensure(request.status==='WAITING_MERCHANT','请求已关闭')
    validateOrderItems(db,request)
    ensure(request.totalMinor<=state.rule.transactionMaxMinor,'金额超过当前演示单笔上限')
    const order={id:id(state,'DO'),requestId:request.id,userUid:request.userUid,userId:request.userId,merchantUid:request.merchantUid,merchantId:request.merchantId,storeId:request.storeId,retailOrderIds:request.retailOrderIds,items:copy(request.items),totalMinor:request.totalMinor,currency:'CNY',status:'DRAFT',fulfillmentStatus:'NOT_READY',ruleCode:state.rule.code,ruleSnapshot:copy(state.rule),version:1,createdAt:iso(now),remark:request.remark}
    order.productVersions=Object.fromEntries(order.items.map(item=>[item.productId,Number(db.goods.find(g=>g.id===item.productId).version||1)]))
    order.snapshotHash=JSON.stringify([order.items,order.totalMinor,order.ruleCode,order.productVersions])
    event(state,'ORDER_CREATED',{orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,amountMinor:order.totalMinor},now,who)
    order.status='PUBLISHED';order.version++;order.confirmationCredential=credential(state,order,'CONFIRM',now);order.expiresAt=order.confirmationCredential.expiresAt
    state.orders.push(order);request.status='PUBLISHED';request.orderId=order.id;retailState(db,order,'待顾客确认')
    event(state,'ORDER_PUBLISHED',{orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,amountMinor:order.totalMinor},now,who)
    return order
  }
  if(action==='cancel-request'){
    const request=byId(state.requests,data.requestId,'请求');ensure(actor.role==='ROLE_USER'&&request.userUid===uid(actor),'只能取消自己的请求');ensure(request.status==='WAITING_MERCHANT','商家已发布，请使用订单取消')
    request.status='CANCELLED';for(const row of db.orders)if(request.retailOrderIds.includes(row.id))row.status='已取消';return request
  }
  if(['confirm','fulfillment-code','redeem','cancel'].includes(action)){
    const order=byId(state.orders,data.orderId,'先吃后付订单');scopeOrder(actor,order)
    if(action==='cancel'){ensure((actor.role==='ROLE_USER'&&uid(actor)===order.userUid)||(actor.role==='ROLE_UNIT'&&mid(actor)===order.merchantUid),'仅本人或本店经营者可取消');return release(db,state,order,'CANCELLED',now,who)}
    if(action==='confirm'){
      userOnly(actor)
      const key=`CONFIRM:${order.id}:${data.credential}`
      const fingerprint=JSON.stringify([data.version,data.snapshotHash,data.agreementAccepted])
      if(state.idempotency[key]){ensure(state.idempotency[key].fingerprint===fingerprint,'同一确认标识的参数发生变化');return order}
      ensure(order.status==='PUBLISHED','订单当前不能确认')
      ensure(data.agreementAccepted===true,'请主动确认演示交易与条款说明')
      ensure(Number(data.version)===order.version&&data.snapshotHash===order.snapshotHash,'订单快照已变化，请重新读取')
      checkCredential(order.confirmationCredential,data.credential,'CONFIRM',now)
      validateOrderItems(db,order,true)
      const account=creditAccount(db,order.userUid,now)
      ensure(!account.blocked,'账户存在冻结、逾期或待处理异议，暂不可新增确认')
      ensure(account.availableMinor>=order.totalMinor,'可用额度不足')
      const reservation={id:id(state,'RSV'),orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,amountMinor:order.totalMinor,status:'HELD',expiresAt:iso(now+order.ruleSnapshot.reservationTtlSeconds*1000)}
      state.reservations.push(reservation);order.reservationId=reservation.id;order.status='CONFIRMED';order.fulfillmentStatus='READY';order.confirmedAt=iso(now);order.version++;order.confirmationCredential.used=true;state.idempotency[key]={resourceId:order.id,fingerprint}
      retailState(db,order,'待商家核销');event(state,'QUOTA_HELD',{orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,amountMinor:order.totalMinor},now,who);return order
    }
    if(action==='fulfillment-code'){
      userOnly(actor);ensure(order.status==='CONFIRMED','只有已确认待履约订单可生成核销码')
      if(order.fulfillmentCredential)order.fulfillmentCredential.revoked=true
      order.fulfillmentCredential=credential(state,order,'FULFILL',now);return order.fulfillmentCredential
    }
    if(action==='redeem'){
      merchantOnly(actor,order.merchantUid)
      if(order.status==='FULFILLED'&&order.fulfilledCredential===data.credential)return order
      ensure(order.status==='CONFIRMED','订单未确认、已核销或已过期')
      checkCredential(order.fulfillmentCredential,data.credential,'FULFILL',now)
      const reservation=byId(state.reservations,order.reservationId,'预占');ensure(reservation.status==='HELD'&&Date.parse(reservation.expiresAt)>now,'预占已过期')
      reservation.status='CONSUMED';order.status='FULFILLED';order.fulfillmentStatus='FULFILLED';order.fulfilledAt=iso(now);order.version++;order.fulfilledCredential=data.credential;order.fulfillmentCredential.used=true
      const receivable={id:id(state,'REC'),orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,storeId:order.storeId,issuedMinor:order.totalMinor,outstandingMinor:order.totalMinor,dueAt:iso(now+order.ruleSnapshot.termDays*86400000),status:'OPEN',version:1,createdAt:iso(now)}
      state.receivables.push(receivable);order.receivableId=receivable.id;retailState(db,order,'待评价')
      event(state,'RECEIVABLE_ISSUED',{orderId:order.id,receivableId:receivable.id,userUid:order.userUid,merchantUid:order.merchantUid,amountMinor:order.totalMinor},now,who);return order
    }
  }
  if(action==='repay'){
    userOnly(actor);const amount=integer(data.amountMinor);ensure(data.clientKey,'缺少还款幂等标识')
    const key=`REPAY:${uid(actor)}:${data.clientKey}`
    const fingerprint=JSON.stringify([data.merchantUid,[...(data.receivableIds||[])].sort(),amount])
    if(state.idempotency[key]){ensure(state.idempotency[key].fingerprint===fingerprint,'相同还款幂等标识的参数发生变化');return byId(state.repayments,state.idempotency[key].resourceId,'还款单')}
    const list=(data.receivableIds||[]).map(value=>byId(state.receivables,value,'应收'))
    ensure(list.length>0,'请选择应还账单');ensure(list.every(r=>r.userUid===uid(actor)&&r.merchantUid===data.merchantUid),'只能向同一商家偿还本人账单')
    ensure(list.some(r=>r.outstandingMinor>0),'账单已结清')
    const repayment={id:id(state,'PAY'),reference:id(state,'MOCKREF'),userUid:uid(actor),merchantUid:data.merchantUid,receivableIds:list.map(r=>r.id),amountMinor:amount,receivedMinor:0,unallocatedMinor:0,allocations:[],method:'MOCK_ONLINE',status:'PENDING',createdAt:iso(now),creatorId:who,version:1,environment:'SIMULATION'}
    state.repayments.push(repayment);state.idempotency[key]={resourceId:repayment.id,fingerprint};event(state,'REPAYMENT_PENDING',{repaymentId:repayment.id,userUid:repayment.userUid,merchantUid:repayment.merchantUid,amountMinor:amount},now,who);return repayment
  }
  if(action==='simulate-result'){
    persona(actor,data,'701')
    const repayment=byId(state.repayments,data.repaymentId,'还款单')
    ensure(data.reference===repayment.reference&&data.merchantUid===repayment.merchantUid&&Number(data.amountMinor)===repayment.amountMinor&&data.currency==='CNY'&&data.environment==='SIMULATION','模拟结果的业务号、商家、金额、币种或环境不匹配')
    ensure(data.providerEventId,'缺少模拟事件标识')
    const fingerprint=JSON.stringify([data.reference,data.merchantUid,Number(data.amountMinor),data.currency,data.environment,data.result])
    const prior=state.providerEvents[data.providerEventId]
    if(prior){ensure(prior.repaymentId===repayment.id&&prior.fingerprint===fingerprint,'同一渠道事件内容不一致');return repayment}
    state.providerEvents[data.providerEventId]={repaymentId:repayment.id,fingerprint,occurredAt:data.occurredAt||iso(now)}
    if(data.result==='TIMEOUT'){repayment.channelResult='UNKNOWN';return repayment}
    if(['FAILED','CLOSED'].includes(data.result)){ensure(repayment.status==='PENDING','当前状态不可失败或关单');repayment.status=data.result;repayment.channelResult=data.result;repayment.version++;event(state,'REPAYMENT_'+data.result,{repaymentId:repayment.id,userUid:repayment.userUid,merchantUid:repayment.merchantUid},now,who);return repayment}
    ensure(data.result==='CONFIRMED','未知模拟结果')
    if(repayment.status==='CONFIRMED'||repayment.status==='EXCEPTION')return repayment
    repayment.receivedMinor=repayment.amountMinor
    if(['FAILED','CLOSED'].includes(repayment.status)){repayment.status='EXCEPTION';repayment.channelResult='CONFIRMED_LATE';repayment.unallocatedMinor=repayment.amountMinor;repayment.version++;event(state,'LATE_RESULT_EXCEPTION',{repaymentId:repayment.id,userUid:repayment.userUid,merchantUid:repayment.merchantUid,amountMinor:repayment.amountMinor},now,who);return repayment}
    ensure(repayment.status==='PENDING','当前状态无法确认')
    allocate(state,repayment,now,who);return repayment
  }
  if(action==='request-exception-resolution'){
    persona(actor,data,'401')
    const repayment=byId(state.repayments,data.repaymentId,'还款单');ensure(repayment.status==='EXCEPTION','当前不是待复核差异')
    ensure(String(data.evidence||'').trim(),'请填写差异核对依据')
    const previous=state.resolutionRequests.find(r=>r.repaymentId===repayment.id&&r.status==='REQUESTED')
    if(previous)return previous
    const request={id:id(state,'REVIEW'),repaymentId:repayment.id,repaymentVersion:repayment.version,creatorId:who,evidence:String(data.evidence).slice(0,800),status:'REQUESTED',createdAt:iso(now)}
    state.resolutionRequests.push(request);return request
  }
  if(action==='resolve-exception'){
    persona(actor,data,'402')
    const request=byId(state.resolutionRequests,data.resolutionId,'差异复核申请')
    ensure(request.creatorId!==who,'申请人与独立复核人必须不同')
    const repayment=byId(state.repayments,request.repaymentId,'还款单')
    if(request.status==='DONE')return repayment
    ensure(request.status==='REQUESTED'&&repayment.status==='EXCEPTION','当前不处于待复核状态')
    ensure(request.repaymentVersion===repayment.version,'还款单版本已变化，请重新提交复核申请')
    allocate(state,repayment,now,who);request.status='DONE';request.checkerId=who;request.resolvedAt=iso(now);return repayment
  }
  if(action==='refund-create'){
    const order=byId(state.orders,data.orderId,'先吃后付订单');scopeOrder(actor,order)
    ensure(actor.role==='ROLE_USER'||actor.role==='ROLE_UNIT','退款申请须由顾客本人或所属商家发起')
    ensure(order.status==='FULFILLED','未核销请使用取消订单，只有已履约订单可申请售后')
    const amount=integer(data.requestedMinor);ensure(amount<=100000000,'申请金额超过原规则上限')
    ensure(String(data.reason||'').trim(),'请填写售后原因');ensure(data.clientKey,'缺少申请幂等标识')
    const key=`REFUND_REQUEST:${who}:${data.clientKey}`,fingerprint=JSON.stringify([order.id,amount,String(data.reason).trim()])
    if(state.idempotency[key]){ensure(state.idempotency[key].fingerprint===fingerprint,'同一售后标识的内容发生变化');return byId(state.refunds,state.idempotency[key].resourceId,'售后单')}
    const pending=state.refunds.filter(r=>r.orderId===order.id&&r.status==='REQUESTED').reduce((sum,r)=>sum+r.requestedMinor,0)
    ensure(amount<=order.totalMinor-(order.refundedMinor||0)-(order.adjustedMinor||0)-pending,'申请超过当前剩余可退金额（已扣除待审核申请）')
    const refund={id:id(state,'RFN'),orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,storeId:order.storeId,creatorId:who,requestedMinor:amount,principalReductionMinor:0,returnPayableMinor:0,returnedMinor:0,status:'REQUESTED',merchantDecision:'PENDING',reason:String(data.reason).trim().slice(0,500),version:1,attempt:0,createdAt:iso(now),history:[{type:'REQUESTED',actorId:who,at:iso(now),note:String(data.reason).trim().slice(0,500)}],environment:'SIMULATION'}
    refund.reference='SIM-REFUND-'+refund.id;state.refunds.push(refund);state.idempotency[key]={resourceId:refund.id,fingerprint}
    event(state,'REFUND_REQUESTED',{refundId:refund.id,orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,amountMinor:amount},now,who);return refund
  }
  if(action==='refund-merchant-response'){
    const refund=byId(state.refunds,data.refundId,'售后单');merchantOnly(actor,refund.merchantUid)
    ensure(refund.status==='REQUESTED','该售后已进入财务处理，商家不能更改原意见')
    ensure(['SUPPORT','DECLINE'].includes(data.decision),'请选择处理意见');ensure(String(data.reason||'').trim(),'请填写处理说明')
    const fingerprint=JSON.stringify([data.decision,String(data.reason).trim()])
    if(refund.merchantResponseFingerprint===fingerprint)return refund
    refund.merchantDecision=data.decision;refund.merchantReason=String(data.reason).trim().slice(0,500);refund.merchantActorId=who;refund.merchantRespondedAt=iso(now);refund.merchantResponseFingerprint=fingerprint;refund.version++
    refund.history.push({type:'MERCHANT_RESPONSE',actorId:who,at:iso(now),note:refund.merchantReason,decision:data.decision})
    return refund
  }
  if(action==='refund-decision'){
    persona(actor,data,'402');const refund=byId(state.refunds,data.refundId,'售后单')
    ensure(who!==refund.creatorId,'退款申请人与独立复核人必须不同')
    ensure(['APPROVE','REJECT'].includes(data.decision),'请选择通过或拒绝');ensure(String(data.reason||'').trim(),'请填写复核理由')
    const key=`REFUND_DECISION:${refund.id}:${data.clientKey||data.version}`,fingerprint=JSON.stringify([Number(data.version),data.decision,String(data.reason).trim()])
    if(state.idempotency[key]){ensure(state.idempotency[key].fingerprint===fingerprint,'同一复核标识内容发生变化');return refund}
    ensure(refund.status==='REQUESTED'&&Number(data.version)===refund.version,'售后单已变化，请刷新后处理')
    if(data.decision==='REJECT')refund.status='REJECTED'
    else {
      const order=byId(state.orders,refund.orderId,'订单'),receivable=byId(state.receivables,order.receivableId,'应收')
      ensure(refund.requestedMinor<=order.totalMinor-(order.refundedMinor||0)-(order.adjustedMinor||0),'剩余可退金额已变化')
      const reduction=Math.min(refund.requestedMinor,receivable.outstandingMinor),payable=refund.requestedMinor-reduction
      if(reduction>0){receivable.outstandingMinor-=reduction;receivable.refundedPrincipalMinor=(receivable.refundedPrincipalMinor||0)+reduction;receivable.status=receivable.outstandingMinor===0?'SETTLED':'PARTIAL';receivable.version++}
      order.refundedMinor=(order.refundedMinor||0)+refund.requestedMinor;order.version++
      refund.principalReductionMinor=reduction;refund.returnPayableMinor=payable;refund.status=payable>0?'PROCESSING':'SUCCEEDED';refund.approvedAt=iso(now);refund.attempt=payable>0?1:0
      event(state,'REFUND_APPROVED',{refundId:refund.id,orderId:order.id,userUid:order.userUid,merchantUid:order.merchantUid,amountMinor:refund.requestedMinor,principalReductionMinor:reduction,returnPayableMinor:payable},now,who)
    }
    refund.checkerId=who;refund.decisionReason=String(data.reason).trim().slice(0,500);refund.decidedAt=iso(now);refund.version++;refund.history.push({type:data.decision==='APPROVE'?'APPROVED':'REJECTED',actorId:who,at:iso(now),note:refund.decisionReason,principalReductionMinor:refund.principalReductionMinor,returnPayableMinor:refund.returnPayableMinor})
    state.idempotency[key]={resourceId:refund.id,fingerprint};return refund
  }
  if(action==='simulate-refund-result'){
    persona(actor,data,'701');const refund=byId(state.refunds,data.refundId,'售后单')
    ensure(data.reference===refund.reference&&data.merchantUid===refund.merchantUid&&Number(data.amountMinor)===refund.returnPayableMinor&&data.currency==='CNY'&&data.environment==='SIMULATION','退款模拟结果的业务号、商家、金额、币种或环境不匹配')
    ensure(refund.returnPayableMinor>0,'这笔售后仅冲本金，无需模拟现金退回');ensure(data.providerEventId,'缺少事件标识')
    const fingerprint=JSON.stringify([refund.reference,data.merchantUid,Number(data.amountMinor),data.currency,data.environment,data.result]),prior=state.providerEvents[data.providerEventId]
    if(prior){ensure(prior.refundId===refund.id&&prior.fingerprint===fingerprint,'同一退款事件的内容不一致');return refund}
    if(refund.status==='SUCCEEDED'&&refund.returnedMinor===refund.returnPayableMinor){ensure(data.result==='CONFIRMED','已完成的退款不能改记失败');state.providerEvents[data.providerEventId]={refundId:refund.id,fingerprint};return refund}
    ensure(['PROCESSING','FAILED','EXCEPTION'].includes(refund.status)&&refund.returnedMinor===0,'当前退款状态不可处理此结果')
    if(data.result==='CONFIRMED'){
      refund.returnedMinor=refund.returnPayableMinor;refund.status='SUCCEEDED';refund.channelResult='CONFIRMED';refund.confirmedEventId=data.providerEventId;refund.completedAt=iso(now);refund.failureConfirmed=false
      event(state,'REFUND_RETURNED',{refundId:refund.id,orderId:refund.orderId,userUid:refund.userUid,merchantUid:refund.merchantUid,amountMinor:refund.returnedMinor},now,who)
    } else {
      ensure(refund.status==='PROCESSING','仅处理中的退款可记录超时或明确失败')
      ensure(['TIMEOUT','FAILED'].includes(data.result),'未知退款模拟结果')
      refund.channelResult=data.result==='TIMEOUT'?'UNKNOWN':'FAILED';refund.failureConfirmed=data.result==='FAILED';if(data.result==='FAILED')refund.status='FAILED'
    }
    refund.version++;refund.history.push({type:data.result==='CONFIRMED'?'RETURN_SUCCEEDED':data.result==='TIMEOUT'?'RETURN_UNKNOWN':'RETURN_FAILED',actorId:who,at:iso(now),note:data.result==='CONFIRMED'?'模拟现金退回完成，不再释放额度':data.result==='TIMEOUT'?'结果未知，不按失败重试':'明确演示失败，待独立核查后重试'})
    state.providerEvents[data.providerEventId]={refundId:refund.id,fingerprint};return refund
  }
  if(action==='refund-retry'){
    persona(actor,data,'402');const refund=byId(state.refunds,data.refundId,'售后单')
    ensure(who!==refund.creatorId,'原申请人不能独立复核重试')
    const key=`REFUND_RETRY:${refund.id}:${data.clientKey||data.version}`,fingerprint=JSON.stringify([Number(data.version),String(data.reason||'')])
    if(state.idempotency[key]){ensure(state.idempotency[key].fingerprint===fingerprint,'同一重试标识的内容发生变化');return refund}
    ensure(Number(data.version)===refund.version,'售后单版本已变化，请刷新')
    ensure(['FAILED','EXCEPTION'].includes(refund.status)&&refund.failureConfirmed===true,'只有已核实失败的退款可重试，不能把超时当失败')
    ensure(refund.returnedMinor===0&&refund.returnPayableMinor>0,'已退回或没有待退金额，不可重试')
    refund.status='PROCESSING';refund.failureConfirmed=false;refund.channelResult='PENDING';refund.retryCheckerId=who;refund.attempt++;refund.version++;refund.history.push({type:'RETURN_RETRY',actorId:who,at:iso(now),note:String(data.reason||'独立核查失败后重试').slice(0,500)})
    state.idempotency[key]={resourceId:refund.id,fingerprint};return refund
  }
  if(action==='dispute'){
    userOnly(actor);const r=byId(state.receivables,data.receivableId,'账单');ensure(r.userUid===uid(actor),'不能对他人账单提出异议');ensure(String(data.reason||'').trim(),'请说明异议原因');const existing=state.disputes.find(d=>d.receivableId===r.id&&['OPEN','REVIEWING','APPEALED'].includes(d.status));if(existing)return existing
    const dispute={id:id(state,'DSP'),receivableId:r.id,userUid:r.userUid,merchantUid:r.merchantUid,reason:String(data.reason).slice(0,500),status:'OPEN',createdAt:iso(now)};state.disputes.push(dispute);return dispute
  }
  if(action==='resolve-dispute'){
    persona(actor,data,'501');const dispute=byId(state.disputes,data.disputeId,'异议');ensure(String(data.resolution||'').trim(),'请填写处理说明');dispute.status='RESOLVED';dispute.resolution=String(data.resolution).slice(0,500);dispute.resolvedAt=iso(now);return dispute
  }
  if(action==='draft-rule'){
    persona(actor,data,'401')
    const rule={code:`RV-SIM-UI-${state.ruleDrafts.length+1}`,quotaMinor:integer(data.quotaMinor),transactionMaxMinor:integer(data.transactionMaxMinor),termDays:integer(data.termDays),credentialTtlSeconds:integer(data.credentialTtlSeconds),reservationTtlSeconds:integer(data.reservationTtlSeconds),fixtureOnly:true,formalApproval:false}
    ensure(rule.quotaMinor<=1000000&&rule.transactionMaxMinor<=1000000,'原演示规则的额度与单笔上限均不得超过1000000分')
    ensure(rule.termDays<=365,'期限必须为1至365天')
    ensure(rule.credentialTtlSeconds>=30&&rule.credentialTtlSeconds<=600,'确认凭证有效期须为30至600秒')
    ensure(rule.reservationTtlSeconds>=60&&rule.reservationTtlSeconds<=3600,'预占有效期须为60至3600秒')
    ensure(rule.transactionMaxMinor<=rule.quotaMinor,'单笔上限不能大于总额度')
    const draft={id:id(state,'RULE'),rule,status:'DRAFT',creatorId:who,createdAt:iso(now)};state.ruleDrafts.push(draft);return draft
  }
  if(action==='approve-rule'){
    persona(actor,data,'402');const draft=byId(state.ruleDrafts,data.ruleId,'规则草稿');ensure(draft.creatorId!==who,'申请人与复核人必须不同');if(draft.status==='APPROVED')return draft
    const userUids=[...new Set(state.orders.map(o=>o.userUid))]
    ensure(userUids.every(user=>{const a=creditAccount(db,user,now);return draft.rule.quotaMinor>=a.principalMinor+a.reservedMinor}),'新额度不能小于任一用户当前本金和预占之和')
    state.rule=copy(draft.rule);draft.status='APPROVED';draft.checkerId=who;draft.approvedAt=iso(now);return draft
  }
  throw new Error('未支持的先吃后付操作')
}
