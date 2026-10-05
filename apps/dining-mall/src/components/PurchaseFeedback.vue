<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Star, ArrowRight, Document } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { sanitizeHTML } from '@/utils/sanitize'
const props=defineProps({orderId:[String,Number],creditOrderId:String,compact:Boolean})
const emit=defineEmits(['updated'])
const router=useRouter(),orders=ref([]),cases=ref([]),credit=ref(null),loaded=ref(false),busy=ref(false),reviewOpen=ref(false),refundOpen=ref(false),target=ref(null),reviewText=ref(''),rate=ref(5),reason=ref(''),amount=ref(''),clientKey=ref('')
const money=value=>(Number(value||0)/100).toFixed(2)
const linkedCredit=computed(()=>props.creditOrderId||orders.value[0]?.creditOrderId||'')
const relatedCases=computed(()=>cases.value.filter(r=>linkedCredit.value?r.creditOrderId===linkedCredit.value:r.retailOrderIds?.includes(Number(props.orderId))))
const originalMinor=computed(()=>credit.value?.totalMinor??Math.round((orders.value[0]?.price||0)*100))
const remainingMinor=computed(()=>Math.max(0,originalMinor.value-(credit.value?.adjustedMinor||0)-relatedCases.value.filter(r=>r.status!=='REJECTED').reduce((sum,r)=>sum+r.requestedMinor,0)))
const eligible=computed(()=>orders.value.some(o=>o.canAfterSales))
let loadVersion=0
async function load(){const version=++loadVersion,params=props.creditOrderId?{creditOrderId:props.creditOrderId}:props.orderId?{orderId:props.orderId}:{};const [o,r]=await Promise.all([request.get('/feedback/orders',{params}),request.get('/after-sales')]);const nextOrders=o.data||[],linked=props.creditOrderId||nextOrders[0]?.creditOrderId;let nextCredit=null;if(linked){const c=await request.get('/credit/overview');nextCredit=c.data?.orders.find(x=>x.id===linked)||null}if(version!==loadVersion)return;orders.value=nextOrders;cases.value=r.data||[];credit.value=nextCredit;loaded.value=true}

function startReview(order){if(!order.canReview)return ElMessage.info(order.reviewReason);target.value=order;reviewText.value='';rate.value=5;clientKey.value='REVIEW-'+Date.now()+'-'+Math.random().toString(16).slice(2);reviewOpen.value=true}
async function saveReview(){if(busy.value)return;busy.value=true;try{const r=await request.post('/reviews',{orderId:target.value.id,text:reviewText.value,rate:rate.value,clientKey:clientKey.value});if(r.code!=='200')return ElMessage.error(r.msg);reviewOpen.value=false;await load();emit('updated');ElMessage.success('评价已保存，可在餐品和店铺页面查看')}finally{busy.value=false}}
function startRefund(){if(!eligible.value)return ElMessage.info('完成履约后可申请售后；尚未核销请使用取消订单');if(remainingMinor.value<=0)return ElMessage.info('当前可申请金额为0，请查看已有售后进度');amount.value=money(remainingMinor.value);reason.value='';clientKey.value='AFTERSALE-'+Date.now()+'-'+Math.random().toString(16).slice(2);refundOpen.value=true}
async function saveRefund(){if(busy.value)return;const raw=Number(amount.value)*100,minor=Math.round(raw);if(!Number.isFinite(raw)||minor<=0||Math.abs(raw-minor)>0.000001||minor>remainingMinor.value)return ElMessage.warning('金额须大于0、最多两位小数，且不超过可申请金额');busy.value=true;try{const payload={orderId:orders.value[0]?.id,creditOrderId:linkedCredit.value||undefined,requestedMinor:minor,reason:reason.value,clientKey:clientKey.value};const r=await request.post('/after-sales',payload);if(r.code!=='200')return ElMessage.error(r.msg);refundOpen.value=false;await load();emit('updated');ElMessage.success('售后申请已提交，可查看商家处理和后续进度');router.push({path:'/mall/afterSale',query:{id:r.data.id}})}finally{busy.value=false}}
const sync=event=>{if(event.key==='mall-ui-data-v2')load()}
onMounted(()=>{load();window.addEventListener('storage',sync)});onUnmounted(()=>{loadVersion++;window.removeEventListener('storage',sync)});watch(()=>[props.orderId,props.creditOrderId],load)
</script>
<template>
<section v-if="loaded" class="native-card purchase-feedback" data-testid="purchase-feedback">
  <h3 class="accent-title">评价与售后</h3>
  <div v-if="!orders.length" class="native-empty">订单不存在或不属于当前顾客</div>
  <article v-for="o in orders" :key="o.id" class="purchase-feedback-item" :data-order-id="o.id">
    <div class="purchase-feedback-title"><img :src="o.img" :alt="o.name"/><div><h4>{{o.name}}</h4><p>{{o.reviewReason}}</p></div><button v-if="o.canReview" class="primary-pill" @click="startReview(o)">去评价</button><span v-else-if="o.reviewed" class="reviewed-badge">已评价</span></div>
    <div v-if="o.reviewed" class="saved-purchase-review"><div class="stars" :aria-label="'评分'+o.rate+'星'">{{'★'.repeat(o.rate)}}<small>本机示例订单评价</small></div><div class="native-rich-content" v-html="sanitizeHTML(o.comment)"></div><p v-if="o.reply" class="review-reply">商家回复：<span v-html="sanitizeHTML(o.reply)"></span></p></div>
    <div class="feedback-links"><button @click="router.push({path:'/mall/detail',query:{id:o.goodsId}})">查看餐品与评价 <ArrowRight/></button><button @click="router.push({path:'/mall/shop',query:{id:o.unitId}})">查看店铺评价 <ArrowRight/></button></div>
  </article>
  <div v-if="orders.length" class="feedback-after-sales"><div><strong>售后申请</strong><small>可申请 ¥{{money(remainingMinor)}} {{linkedCredit?'（整笔先吃后付订单）':''}}</small></div><button v-if="eligible && remainingMinor>0" class="outline-pill" @click="startRefund">申请售后 / 退款</button><span v-else class="muted">{{eligible?'已申请至上限':'完成履约后可申请'}}</span></div>
  <button v-if="relatedCases.length" class="feedback-progress-link" @click="router.push('/mall/afterSales')"><Document/>查看已有售后进度（{{relatedCases.length}}）<ArrowRight/></button>
  <p class="feedback-note">评价不会改变应还金额。未履约用取消，已履约的信用退款须独立复核，不能直接取消消债。</p>
</section>
<div v-else class="native-card">正在读取评价与售后记录…</div>
<div v-if="reviewOpen" class="native-overlay" @click.self="reviewOpen=false"><section class="native-sheet" data-testid="review-editor"><button class="sheet-close" @click="reviewOpen=false">×</button><h3>评价餐品</h3><p>{{target.name}}</p><p>仅针对本人已履约的示例订单，每件餐品可提交一次</p><el-rate v-model="rate"/><form @submit.prevent="saveReview"><textarea class="review-input" v-model="reviewText" placeholder="填写你的评价（1至500字）" required maxlength="500"></textarea><button type="submit" class="primary-pill full" :disabled="busy">提交评价</button></form></section></div>
<div v-if="refundOpen" class="native-overlay" @click.self="refundOpen=false"><section class="native-sheet" data-testid="after-sales-editor"><button class="sheet-close" @click="refundOpen=false">×</button><h3>申请售后 / 退款</h3><p>{{credit?.items?.map(o=>o.name).join('、')||orders.map(o=>o.name).join('、')}}</p><form class="native-form" @submit.prevent="saveRefund"><label>申请金额（元）<input v-model="amount" type="number" min="0.01" step="0.01" :max="money(remainingMinor)" required aria-label="售后申请金额"/></label><button type="button" class="credit-text-button" @click="amount=money(remainingMinor)">申请全部可退金额 ¥{{money(remainingMinor)}}</button><label>售后原因<textarea v-model="reason" placeholder="说明需要售后的原因，使用示例资料" required maxlength="500"></textarea></label><p v-if="linkedCredit">提交申请不会立即改变应还本金。独立审核通过后，先冲减未还本金，剩余部分才进入模拟现金退回。</p><p v-else>普通结算尚未接支付退款，本流程保存商家处理意见，不把处理完成当作真实退款到账。</p><button type="submit" class="primary-pill full" :disabled="busy">提交售后申请</button></form></section></div>
</template>
<style scoped>
.purchase-feedback-item{padding:15px 0;border-bottom:1px solid #f0f0f0}.purchase-feedback-title{display:flex;align-items:center;gap:10px}.purchase-feedback-title>img{width:52px;height:52px;object-fit:cover;border-radius:6px}.purchase-feedback-title>div{flex:1;min-width:0}.purchase-feedback-title h4{font-size:13px;line-height:1.5}.purchase-feedback-title p{font-size:11px;color:#aaa;margin-top:5px}.purchase-feedback-title .primary-pill{font-size:11px;padding:8px 12px}.reviewed-badge{font-size:11px;color:#a99b8f;border:1px solid #e8e1db;border-radius:4px;padding:4px 6px;white-space:nowrap}.saved-purchase-review{padding:13px 0 3px;font-size:13px}.stars{color:#ef9d35;font-size:16px}.stars small{font-size:10px;color:#aaa;margin-left:8px}.saved-purchase-review .native-rich-content{margin-top:9px}.review-reply{background:#f8f7f6;color:#88715d;padding:10px;font-size:12px;line-height:1.7}.feedback-links{display:flex;gap:16px;margin-top:12px}.feedback-links button{font-size:11px;color:#aa744b;padding:0}.feedback-links svg{width:12px;height:12px}.feedback-after-sales{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:20px}.feedback-after-sales strong{display:block;font-size:14px}.feedback-after-sales small{display:block;font-size:10px;color:#999;margin-top:6px}.feedback-after-sales .muted{font-size:11px}.outline-pill{border:1px solid #ffc3ad!important;background:#fff4ee!important;color:#e3673b!important;padding:9px 11px!important;border-radius:20px;font-size:12px!important;white-space:nowrap}.feedback-progress-link{display:flex;align-items:center;gap:8px;font-size:12px!important;color:#b37c52!important;margin-top:18px;width:100%}.feedback-progress-link svg{width:16px;height:16px}.feedback-progress-link svg:last-child{margin-left:auto}.feedback-note{color:#aaa;font-size:10px!important;line-height:1.8!important;margin-top:15px!important}
</style>
