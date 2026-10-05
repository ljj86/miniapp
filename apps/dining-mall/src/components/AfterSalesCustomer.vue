<script setup>
import {ref,computed,onMounted,onUnmounted,watch} from 'vue'
import {useRouter} from 'vue-router'
import {ArrowRight,Refresh,Document} from '@element-plus/icons-vue'
import request from '@/utils/request'
const props=defineProps({caseId:String})
const router=useRouter(),cases=ref([]),loaded=ref(false)
const current=computed(()=>cases.value.find(r=>r.id===props.caseId))
const money=value=>(Number(value||0)/100).toFixed(2)
const date=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—'
function status(row){if(row.kind==='ordinary')return ({REQUESTED:'待商家处理',ACCEPTED:'商家已同意 · 退款渠道未接入',REJECTED:'商家未同意'})[row.status]||row.status;if(row.status==='REQUESTED')return row.merchantDecision==='PENDING'?'待商家反馈':'商家已反馈 · 待独立复核';return ({PROCESSING:'待模拟退回',SUCCEEDED:'处理完成',REJECTED:'未通过复核',FAILED:'退回失败 · 待核查',EXCEPTION:'退回异常 · 待复核'})[row.status]||row.status}
const historyLabels={REQUESTED:'顾客提交申请',MERCHANT_RESPONSE:'商家处理意见',APPROVED:'独立审核通过',REJECTED:'独立审核未通过',RETURN_SUCCEEDED:'模拟现金已退回',RETURN_UNKNOWN:'渠道结果仍未知',RETURN_FAILED:'演示退回明确失败',RETURN_RETRY:'独立核查后重试'}
const actorLabel=id=>({'101':'顾客','201':'本店商家','402':'独立复核人','701':'模拟结果控制员'})[id]||'当前操作人'
async function load(){const r=await request.get('/after-sales');cases.value=r.data||[];loaded.value=true}
function sourceOrder(row){router.push(row.creditOrderId?{path:'/mall/deferredOrder',query:{id:row.creditOrderId}}:{path:'/mall/orderDetail',query:{id:row.retailOrderId}})}
const sync=e=>{if(e.key==='mall-ui-data-v2')load()};let timer
onMounted(()=>{load();timer=setInterval(load,30000);window.addEventListener('storage',sync)})
onUnmounted(()=>{clearInterval(timer);window.removeEventListener('storage',sync)})
watch(()=>props.caseId,load)
</script>
<template>
<div v-if="loaded" class="after-sales-customer" :class="caseId?'has-case':'case-list'">
<template v-if="!caseId"><div class="after-sales-list-heading"><strong>售后 / 退款</strong><button @click="load"><Refresh/>刷新进度</button></div><section v-for="row in cases" :key="row.id" class="native-card after-sales-case" @click="router.push({path:'/mall/afterSale',query:{id:row.id}})"><div class="after-sales-card-top"><span>{{row.id}}</span><strong>{{status(row)}}</strong></div><article class="native-product-row compact"><img :src="row.img" :alt="row.title"/><div><h4>{{row.title}}</h4><p>{{row.merchantName}}</p><span>申请金额 ¥{{money(row.requestedMinor)}}</span></div></article><p class="after-sales-reason">{{row.reason}}</p><div class="after-sales-card-footer"><small>{{date(row.createdAt)}}</small><span>查看处理进度 <ArrowRight/></span></div></section><div v-if="!cases.length" class="native-empty"><Document/><h3>暂无售后申请</h3><p>从已履约订单的“评价与售后”发起申请</p><button class="primary-pill" @click="router.push('/mall/orders')">查看我的订单</button></div></template>
<template v-else-if="current"><section class="after-sales-state"><span>售后单 {{current.id}}</span><h2>{{status(current)}}</h2><p>申请 ¥{{money(current.requestedMinor)}}</p></section><section class="native-card"><article class="native-product-row compact" @click="sourceOrder(current)"><img :src="current.img" :alt="current.title"/><div><h4>{{current.title}}</h4><p>{{current.merchantName}}</p><button class="text-link">查看原订单 <ArrowRight/></button></div></article><div class="native-line"><span>申请原因</span></div><p>{{current.reason}}</p><p v-if="current.merchantReason"><strong>商家{{current.merchantDecision==='SUPPORT'?'支持申请':'不认可申请'}}：</strong>{{current.merchantReason}}</p><p v-if="current.decisionReason"><strong>独立复核意见：</strong>{{current.decisionReason}}</p></section>
<section v-if="current.kind==='credit'" class="native-card"><h3 class="accent-title">处理金额</h3><div class="native-line"><span>申请总金额</span><strong>¥{{money(current.requestedMinor)}}</strong></div><div class="native-line"><span>已冲减未还本金</span><strong>¥{{money(current.principalReductionMinor)}}</strong></div><div class="native-line"><span>应退已还金额</span><strong>¥{{money(current.returnPayableMinor)}}</strong></div><div class="native-line"><span>已演示退回</span><strong>¥{{money(current.returnedMinor)}}</strong></div><p class="muted">额度只按冲减的未还本金恢复；现金退回不会再次增加额度。原还款记录保持不变。</p><p v-if="current.returnPayableMinor>current.returnedMinor" class="red">仍有 ¥{{money(current.returnPayableMinor-current.returnedMinor)}} 等待模拟退回。本金已结清不表示这部分已退回。</p></section>
<section v-else class="native-card"><h3 class="accent-title">普通商城售后</h3><p>商家处理意见已保存并可回看。普通结算尚未连接支付退款渠道，本记录不代表款项已退回，不改变先吃后付账本。</p></section>
<section class="native-card"><h3 class="accent-title">处理进度</h3><article v-for="(event,index) in current.history" :key="index" class="after-sales-event"><strong>{{historyLabels[event.type]||event.type}}</strong><small>{{actorLabel(event.actorId)}} · {{date(event.at)}}</small><p>{{event.note}}</p><p v-if="event.type==='APPROVED'">冲减本金 ¥{{money(event.principalReductionMinor)}}，应退现金 ¥{{money(event.returnPayableMinor)}}</p></article></section><p class="checkout-disclaimer">本机业务演示，不实际扣款或退款。本轮支持金额售后，不含退货物流和换货流程。</p>
</template>
<div v-else class="native-empty">售后单不存在或不属于当前顾客<button class="primary-pill full" @click="router.push('/mall/afterSales')">返回售后列表</button></div>
</div><div v-else class="native-empty">正在读取售后进度…</div>
</template>
<style scoped>
.after-sales-list-heading{display:flex;align-items:center;justify-content:space-between;padding:16px;font-size:14px}.after-sales-list-heading button{font-size:11px;color:#aaa}.after-sales-list-heading svg{width:13px;height:13px}.after-sales-card-top{display:flex;justify-content:space-between;gap:10px;font-size:10px;padding-bottom:12px;border-bottom:1px solid #f2f2f2}.after-sales-card-top>span{color:#aaa}.after-sales-card-top strong{font-weight:500;color:#d88045;text-align:right}.after-sales-reason{font-size:12px;color:#888;line-height:1.7}.after-sales-card-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;border-top:1px solid #f3f3f3;padding-top:12px}.after-sales-card-footer small{color:#bbb;font-size:10px}.after-sales-card-footer span{font-size:11px;color:#ad7b50}.after-sales-card-footer svg,.text-link svg{width:12px;height:12px}.after-sales-state{padding:28px 20px;background:#fff0e6;color:#bc6a34}.after-sales-state>span{font-size:11px;color:#bf997f}.after-sales-state h2{font-size:22px;margin:15px 0}.after-sales-state p{font-size:17px}.text-link{font-size:12px!important;color:#ac7854!important;padding:0!important}.after-sales-event{border-left:2px solid #efdfd3;padding:13px 0 13px 15px;position:relative}.after-sales-event:before{content:'';width:6px;height:6px;border-radius:50%;background:#d6a277;position:absolute;top:19px;left:-4px}.after-sales-event strong{font-size:13px;font-weight:500}.after-sales-event small{display:block;color:#aaa;font-size:10px;margin-top:7px}.after-sales-event p{font-size:12px;line-height:1.7;margin-top:8px;color:#888}
</style>
