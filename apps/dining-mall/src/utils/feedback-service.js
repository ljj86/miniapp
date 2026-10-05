import { creditAction, creditOverview } from './credit-service.js'
const copy=x=>JSON.parse(JSON.stringify(x))
const check=(ok,message)=>{if(!ok)throw new Error(message)}
const plain=x=>String(x||'').replace(/<[^>]*>/g,'').replace(/&nbsp;|\u00a0/g,' ').trim()
const escape=x=>String(x).replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))
const userUid=id=>`y${String(100+Number(id)).padStart(8,'0')}`
const merchantUid=id=>`s${String(id).padStart(7,'0')}`
const hasReview=o=>plain(o.comment).length>0&&Number.isInteger(Number(o.rate))&&Number(o.rate)>=1&&Number(o.rate)<=5
export function isFulfilledOrder(db,order){
  if(order.creditOrderId)return db.credit?.orders.some(o=>o.id===order.creditOrderId&&o.status==='FULFILLED')||false
  return !!order.fulfilledAt||['待评价','已完成','待退款','已退款'].includes(order.status)
}
export function reviewInfo(db,order,actor){
  const mine=actor.role==='ROLE_USER'&&Number(order.userId)===Number(actor.id)
  return {reviewed:hasReview(order),canReview:mine&&isFulfilledOrder(db,order)&&!hasReview(order),reviewReason:!mine?'仅订单本人可评价':!isFulfilledOrder(db,order)?'完成履约后可评价':hasReview(order)?'已评价，每件订单餐品只能提交一次':'可评价',canAfterSales:mine&&isFulfilledOrder(db,order)}
}
export function publicReviews(db,{goodsId,unitId}={}){
  return db.orders.filter(o=>hasReview(o)&&(o.reviewedAt||isFulfilledOrder(db,o))&&(goodsId===undefined||String(o.goodsId)===String(goodsId))&&(unitId===undefined||String(o.unitId)===String(unitId))).map(o=>({id:o.id,orderId:o.id,userId:o.userId,unitId:o.unitId,goodsId:o.goodsId,name:o.name,img:o.img,rate:o.rate,comment:o.comment,reply:o.reply||'',reviewedAt:o.reviewedAt||null,replyAt:o.replyAt||null,userNickname:db.user.find(u=>u.id===o.userId)?.nickname||'示例用户',userAvatar:db.user.find(u=>u.id===o.userId)?.avatarUrl||'/avatar.svg'})).sort((a,b)=>String(b.reviewedAt||'').localeCompare(String(a.reviewedAt||'')))
}
export function submitReview(db,actor,payload,now=Date.now()){
  const order=db.orders.find(o=>String(o.id)===String(payload.orderId||payload.id));check(order,'订单不存在')
  check(actor.role==='ROLE_USER'&&Number(order.userId)===Number(actor.id),'只能评价本人订单')
  const text=plain(payload.text??payload.comment),rate=Number(payload.rate),fingerprint=JSON.stringify([text,rate])
  if(hasReview(order)){if(payload.clientKey&&payload.clientKey===order.reviewClientKey&&fingerprint===order.reviewFingerprint)return order;throw new Error('此餐品已经评价，请查看已提交的评价')}
  check(isFulfilledOrder(db,order),'完成履约后才能评价，不能为未完成订单生成评价')
  check(Number.isInteger(rate)&&rate>=1&&rate<=5,'评分必须为1至5星');check(text.length>0&&text.length<=500,'请填写1至500字的评价')
  order.comment='<p>'+escape(text).replace(/\n/g,'<br>')+'</p>';order.rate=rate;order.reviewedAt=new Date(now).toISOString();order.reviewClientKey=payload.clientKey||null;order.reviewFingerprint=fingerprint
  if(order.status==='待评价')order.status='已完成'
  return order
}
export function replyReview(db,actor,payload,now=Date.now()){
  const order=db.orders.find(o=>String(o.id)===String(payload.orderId||payload.id));check(order,'订单不存在');check(actor.role==='ROLE_UNIT'&&Number(order.unitId)===Number(actor.id),'只能回复本店订单评价');check(hasReview(order),'顾客尚未评价')
  const text=plain(payload.text??payload.reply);check(text.length>0&&text.length<=500,'请填写1至500字的回复')
  order.reply=escape(text).replace(/\n/g,'<br>');order.replyAt=new Date(now).toISOString();return order
}
export function afterSalesList(db,actor,now=Date.now()){
  db.afterSales=db.afterSales||[]
  const scope=row=>actor.role==='ROLE_ADMIN'||actor.role==='ROLE_USER'&&row.userUid===userUid(actor.id)||actor.role==='ROLE_UNIT'&&row.merchantUid===merchantUid(actor.id)
  const normal=db.afterSales.filter(scope).map(r=>({...copy(r),kind:'ordinary'}))
  if(!['ROLE_USER','ROLE_UNIT','ROLE_ADMIN'].includes(actor.role))return []
  const credit=creditOverview(db,actor,now)
  const refunds=credit.refunds.map(r=>{const order=credit.orders.find(o=>o.id===r.orderId);return {...copy(r),kind:'credit',creditOrderId:r.orderId,retailOrderIds:order?.retailOrderIds||[],title:order?.items.map(i=>i.name).join('、')||r.orderId,img:order?.items[0]?.img||'/avatar.svg',originalMinor:order?.totalMinor||0,merchantName:db.unit.find(u=>'s'+String(u.id).padStart(7,'0')===r.merchantUid)?.nickname||r.merchantUid}})
  return [...normal,...refunds].sort((a,b)=>String(b.createdAt).localeCompare(String(a.createdAt)))
}
export function feedbackOrders(db,actor,{orderId,creditOrderId}={}){
  check(actor.role==='ROLE_USER','请切换为顾客身份')
  return db.orders.filter(o=>Number(o.userId)===Number(actor.id)&&(orderId===undefined||String(o.id)===String(orderId))&&(creditOrderId===undefined||o.creditOrderId===creditOrderId)).map(o=>({...copy(o),...reviewInfo(db,o,actor)}))
}
export function createAfterSales(db,actor,payload,now=Date.now()){
  check(actor.role==='ROLE_USER','售后申请需由订单本人提交')
  const order=payload.creditOrderId?db.orders.find(o=>o.creditOrderId===payload.creditOrderId&&Number(o.userId)===Number(actor.id)):db.orders.find(o=>String(o.id)===String(payload.orderId))
  check(order&&Number(order.userId)===Number(actor.id),'订单不存在或不属于当前顾客');check(isFulfilledOrder(db,order),'未履约请取消订单；履约完成后才能申请售后')
  if(order.creditOrderId)return creditAction(db,actor,'refund-create',{orderId:order.creditOrderId,requestedMinor:payload.requestedMinor,reason:payload.reason,clientKey:payload.clientKey},now)
  db.afterSales=db.afterSales||[]
  const amount=Number(payload.requestedMinor),original=Math.round(Number(order.price)*100),reason=String(payload.reason||'').trim()
  check(Number.isSafeInteger(amount)&&amount>0,'售后金额须为正整数分');check(reason.length>0&&reason.length<=500,'请填写1至500字的售后原因');check(payload.clientKey,'缺少申请幂等标识')
  const fingerprint=JSON.stringify([order.id,amount,reason]),previous=db.afterSales.find(r=>r.creatorId===String(actor.id)&&r.clientKey===payload.clientKey)
  if(previous){check(previous.fingerprint===fingerprint,'同一申请标识内容已变化');return previous}
  const used=db.afterSales.filter(r=>r.retailOrderId===order.id&&r.status!=='REJECTED').reduce((sum,r)=>sum+r.requestedMinor,0);check(amount<=original-used,'超过当前剩余可申请金额')
  const row={id:'AS-'+(Math.max(0,...db.afterSales.map(r=>Number(r.sequence)||0))+1),sequence:Math.max(0,...db.afterSales.map(r=>Number(r.sequence)||0))+1,kind:'ordinary',retailOrderId:order.id,retailOrderIds:[order.id],userUid:userUid(actor.id),merchantUid:merchantUid(order.unitId),creatorId:String(actor.id),title:order.name,img:order.img,merchantName:db.unit.find(u=>u.id===order.unitId)?.nickname||'示例商家',requestedMinor:amount,originalMinor:original,principalReductionMinor:0,returnPayableMinor:0,returnedMinor:0,status:'REQUESTED',merchantDecision:'PENDING',reason,version:1,createdAt:new Date(now).toISOString(),history:[{type:'REQUESTED',at:new Date(now).toISOString(),actorId:String(actor.id),note:reason}],clientKey:payload.clientKey,fingerprint}
  db.afterSales.push(row);return row
}
export function merchantAfterSales(db,actor,payload,now=Date.now()){
  if(String(payload.id).startsWith('RFN-'))return creditAction(db,actor,'refund-merchant-response',{refundId:payload.id,decision:payload.decision,reason:payload.reason},now)
  const row=(db.afterSales||[]).find(r=>r.id===payload.id);check(row,'售后单不存在');check(actor.role==='ROLE_UNIT'&&row.merchantUid===merchantUid(actor.id),'只能处理本店售后');check(['SUPPORT','DECLINE'].includes(payload.decision),'请选择处理意见');check(String(payload.reason||'').trim(),'请填写商家处理说明')
  const fingerprint=JSON.stringify([payload.decision,String(payload.reason).trim()]);if(row.merchantResponseFingerprint===fingerprint)return row
  check(row.status==='REQUESTED','该售后已处理')
  row.merchantDecision=payload.decision;row.merchantReason=String(payload.reason).trim().slice(0,500);row.status=payload.decision==='SUPPORT'?'ACCEPTED':'REJECTED';row.merchantResponseFingerprint=fingerprint;row.merchantRespondedAt=new Date(now).toISOString();row.version++;row.history.push({type:'MERCHANT_RESPONSE',decision:payload.decision,at:row.merchantRespondedAt,actorId:'201',note:row.merchantReason})
  return row
}
