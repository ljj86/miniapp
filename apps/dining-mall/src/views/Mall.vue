<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { House, Grid, ShoppingCart, User, Search, ArrowLeft, ArrowRight, Plus, Minus, Star, Location, Document, Wallet, Setting, Shop, Clock, Collection, SwitchButton, ChatDotRound, Edit, Delete } from '@element-plus/icons-vue'
import request from '@/utils/request'
import {isBackendMode,useDemoMode} from '@/utils/service-mode'
import CreditCustomer from '@/components/CreditCustomer.vue'
import PurchaseFeedback from '@/components/PurchaseFeedback.vue'
import AfterSalesCustomer from '@/components/AfterSalesCustomer.vue'
import SupportCustomer from '@/components/SupportCustomer.vue'
import HelpCustomer from '@/components/HelpCustomer.vue'
import { BASE_RULE } from '@/utils/credit-service'
import { sanitizeHTML } from '@/utils/sanitize'
import { readLocalImage } from '@/utils/local-upload'

const route = useRoute()
const router = useRouter()
const remoteMode=computed(()=>isBackendMode())
const backendUnsupported=computed(()=>remoteMode.value&&!['support','supportChat','supportTickets','supportNew','supportTicket','profile','manuals','manual','materials','updates','notFound'].includes(view.value))
function returnDemo(){useDemoMode();router.push('/login')}
const view = computed(() => route.params.view || 'home')
const products = ref([])
const creditData = ref(null)
const categories = ref([])
const cart = ref([])
const orders = ref([])
const addresses = ref([])
const banners = ref([])
const notices = ref([])
const shops = ref([])
const comments = ref([])
const shopComments = ref([])
const users = ref([])
const detail = ref(null)
const mainPhoto = ref('')
const quantity = ref(1)
const keyword = ref('')
const category = ref('')
const sort = ref('all')
const tab = ref('所有订单')
const selected = ref([])
const selectionReady = ref(false)
const addressId = ref(Number(localStorage.getItem('mall-ui-address')) || null)
const payMode = ref('later')
const note = ref('')
const specOpen = ref(false)
const creditOpen = ref(false)
const addressOpen = ref(false)
const reviewOpen = ref(false)
const addressForm = ref({})
const reviewForm = ref({})
const profileForm = ref({})
const submitting = ref(false)
const acc = ref(JSON.parse(sessionStorage.getItem('account') || '{}'))
const orderTabs = ['所有订单', '待商家发布', '待顾客确认', '待商家核销', '待支付', '待发货', '待收货', '待评价', '已完成', '待退款', '已退款', '已取消']
const title = computed(() => ({ home:'鲜食好店', category:route.query.favorites?'我的收藏':'全部餐品', detail:'餐品详情', cart:'购物车', orders:'我的订单', mine:'我的', checkout:'确认订单', bill:'先吃后付', deferredOrder:'先吃后付订单', orderService:'评价与售后', afterSales:'售后 / 退款', afterSale:'售后进度', address:'收货地址', profile:'个人资料', notFound:'页面不存在', support:'服务中心', supportChat:'店铺客服', supportTickets:'我的留言', supportNew:'填写留言', supportTicket:'留言详情', manuals:'资料与使用说明', manual:'使用说明', materials:'资料中心', updates:'更新记录', shops:'商家列表', shop:'店铺详情', orderDetail:'订单详情' }[view.value] || '商城'))
const go = (page, id) => router.push({ path:'/mall/' + (remoteMode.value&&['home','mine'].includes(page)?'support':page), query:id ? { id } : undefined })
const money = value => Number(value || 0).toFixed(2)
const activeAddress = computed(() => addresses.value.find(a => a.id === addressId.value) || null)
const shop = computed(() => shops.value.find(s => s.id === Number(view.value === 'shop' ? route.query.id : detail.value?.unitId)))
const images = computed(() => [...new Set([detail.value?.img, ...(detail.value?.imgList || '').split('|')].filter(Boolean))])
const availableCart = computed(() => cart.value.filter(c => !c.unavailable))
const selectedCart = computed(() => availableCart.value.filter(c => selected.value.includes(c.id)))
const total = computed(() => selectedCart.value.reduce((sum, c) => sum + c.num * c.goodsPrice, 0))
const creditRule=computed(()=>creditData.value?.rule||BASE_RULE)
const exceedsCreditLimit=computed(()=>{const grouped={};for(const item of selectedCart.value)grouped[item.unitId]=(grouped[item.unitId]||0)+Math.round(item.goodsPrice*100)*item.num;return Object.values(grouped).some(value=>value>creditRule.value.transactionMaxMinor)})
const visibleOrders = computed(() => orders.value.filter(o => tab.value === '所有订单' || o.status === tab.value || tab.value==='待退款'&&o.afterSalesStatuses?.some(s=>['REQUESTED','PROCESSING','FAILED','EXCEPTION','ACCEPTED'].includes(s)) || tab.value==='已退款'&&o.afterSalesStatuses?.includes('SUCCEEDED')))
const currentOrder = computed(() => orders.value.find(o => o.id === Number(route.query.id)))
// Billing presentation is derived from active local orders, so cancellation never leaves a phantom balance.
const bills = computed(() => orders.value.filter(o => o.paymentMode === 'later' && !['已取消', '已退款'].includes(o.status)))
const billTotal = computed(() => bills.value.reduce((sum, o) => sum + o.price, 0))
const filtered = computed(() => {
  let list = products.value.filter(g => view.value==='shop' || ((!category.value || g.typeId === category.value) && (!keyword.value || g.name.toLowerCase().includes(keyword.value.toLowerCase()))))
  if (sort.value === 'sales') list.sort((a,b) => b.sales - a.sales)
  if (sort.value === 'new') list.sort((a,b) => String(b.date).localeCompare(String(a.date)))
  if (view.value === 'shop') list = list.filter(g => g.unitId === Number(route.query.id))
  return list
})
let requestVersion = 0
async function load() {
  const version = ++requestVersion
  if(remoteMode.value){if(view.value==='profile'){const r=await request.get('/web/userInfo');if(version===requestVersion&&r.code==='200')profileForm.value=r.data;else if(r.code!=='200')ElMessage.error(r.msg)}return}
  const response = await Promise.all([
    request.get(route.query.favorites ? '/goods/collect/page' : '/goods/front/page', {params:{pageSize:100}}),
    request.get('/type'), request.get('/cart'), request.get('/orders/front/page',{params:{pageSize:100}}),
    request.get('/address'), request.get('/banner'), request.get('/notice'), request.get('/unit'), request.get('/user')
  ])
  if (version !== requestVersion) return
  const [g,t,c,o,a,b,n,s,u] = response
  products.value = g.data?.records || []
  categories.value = (t.data || []).filter(c => c.status !== false && c.status !== 0)
  cart.value = c.data || []
  orders.value = o.data?.records || []
  addresses.value = a.data || []
  banners.value = b.data || []
  notices.value = n.data || []
  shops.value = s.data || []
  users.value = u.data || []
  if (!addresses.value.some(x => x.id === addressId.value)) addressId.value = addresses.value[0]?.id || null
  if (!selectionReady.value) { selected.value = cart.value.filter(x=>!x.unavailable).map(x => x.id); selectionReady.value = true }
  else selected.value = selected.value.filter(id => cart.value.some(c => c.id === id && !c.unavailable))
  if (view.value === 'detail') {
    const d = await request.get('/goods/' + route.query.id)
    if (version !== requestVersion) return
    detail.value = d.data
    mainPhoto.value = d.data?.img || ''
    quantity.value = 1
    if (detail.value) comments.value = (await request.get('/orders/goodComment/' + detail.value.id)).data || []
  }
  if (view.value === 'shop') {
    const result=await request.get('/reviews/merchant/'+route.query.id)
    if(version===requestVersion)shopComments.value=result.data||[]
  }
  if(acc.value.role==='ROLE_USER'){const credit=await request.get('/credit/overview');if(credit.code==='200')creditData.value=credit.data}
  if (view.value === 'profile') profileForm.value = { ...acc.value }
}
watch(() => route.fullPath, () => { if(route.query.keyword!==undefined)keyword.value=String(route.query.keyword);if(route.query.typeId!==undefined)category.value=Number(route.query.typeId)||''; specOpen.value=false; addressOpen.value=false; reviewOpen.value=false; load(); document.querySelector('.native-app')?.scrollTo(0,0) }, {immediate:true})
const syncBusiness=event=>{if(event.key==='mall-ui-data-v2')load()}
onMounted(()=>window.addEventListener('storage',syncBusiness))
onUnmounted(()=>window.removeEventListener('storage',syncBusiness))
function login() { router.push({path:'/login',query:{returnTo:route.fullPath}}) }
function backMobile() {
 const parents={detail:'category',shop:'shops',shops:'home',cart:'home',checkout:'cart',orders:'mine',orderDetail:'orders',orderService:'orders',afterSales:'mine',afterSale:'afterSales',deferredOrder:'bill',bill:'mine',profile:'mine',support:'mine',supportChat:'support',supportTickets:'support',supportNew:'supportTickets',supportTicket:'supportTickets',manuals:'support',manual:'manuals',materials:'support',updates:'support',address:route.query.select?'checkout':'mine',notFound:'home',category:'home'}
 go(parents[view.value]||'home')
}
function requireUser() {
  if (acc.value.role !== 'ROLE_USER') { ElMessage.info('请先切换为普通用户，账号和密码均为111'); login(); return false }
  return true
}
async function add(goods, count=1) {
  if (!requireUser()) return false
  const result = await request.post('/cart', {goodsId:goods.id,num:count,unitId:goods.unitId})
  if (result.code !== '200') { ElMessage.error(result.msg); return false }
  await load()
  const item = cart.value.find(c => c.goodsId === goods.id)
  if (item && !selected.value.includes(item.id)) selected.value.push(item.id)
  ElMessage.success('已加入购物车')
  return true
}
async function qty(item, count) {
  const r = count < 1 ? await request.delete('/cart/'+item.id) : await request.post('/cart/num',{...item,num:count})
  if (r.code !== '200') ElMessage.error(r.msg)
  await load()
}
async function favorite() {
  if (!requireUser()) return
  const r = await request.post('/collect',{itemId:detail.value.id,userId:acc.value.id})
  detail.value.isCollected = r.code === '200'
  ElMessage.info(detail.value.isCollected ? '已收藏' : '已取消收藏')
}
function checkout() {
  if (!requireUser()) return
  if (!selectedCart.value.length) return ElMessage.info('请选择餐品')
  go('checkout')
}
async function buy() {
  const id=detail.value.id
  if (!await add(detail.value,quantity.value)) return
  selected.value = cart.value.filter(c=>c.goodsId===id).map(c=>c.id)
  go('checkout')
}
async function submit() {
  if (submitting.value || !requireUser() || !selectedCart.value.length) return
  if (!activeAddress.value) return ElMessage.warning('请先添加并选择示例收货地址')
  submitting.value = true
  try {
    const items=selectedCart.value.map(c=>({goodsId:c.goodsId,num:c.num,id:c.id,remark:note.value.trim(),paymentMode:payMode.value}))
    const r=await request.post('/orders/fromCart/'+addressId.value,items)
    if (r.code !== '200') return ElMessage.error(r.msg)
    selected.value=[]
    note.value=''
    ElMessage.success(payMode.value==='later'?'用餐请求已提交，等待商家发布；当前未占额度':'示例订单已保存，不会收款或发货')
    go(payMode.value==='later'?'bill':'orders')
  } finally { submitting.value=false }
}
async function orderAction(order, action) {
  if (action === 'pay') return ElMessage.info('真实支付未接入，不会扣款或改变支付状态')
  const r=await request.get('/orders/'+action+'/'+order.id)
  if (r.code !== '200') return ElMessage.error(r.msg)
  ElMessage.success('已更新本机示例订单')
  await load()
}
function showFavorites() { category.value=''; keyword.value=''; router.push('/mall/category?favorites=1') }
function chooseAddress(address) {
  addressId.value=address.id
  localStorage.setItem('mall-ui-address',String(address.id))
  if (route.query.select) go('checkout')
  else ElMessage.success('已设为本机默认地址')
}
function editAddress(address={}) { addressForm.value={name:'',address:'',phone:'',...address}; addressOpen.value=true }
async function saveAddress() {
  const form=addressForm.value
  if (!form.name.trim() || !form.address.trim() || !form.phone.trim()) return ElMessage.warning('请填写收货人、地址和联系电话（建议使用示例资料）')
  const r=await request.post('/address',form)
  if (r.code !== '200') return ElMessage.error(r.msg)
  addressId.value=r.data.id
  localStorage.setItem('mall-ui-address',String(r.data.id))
  addressOpen.value=false
  await load()
  ElMessage.success('本机地址已保存')
}
async function deleteAddress(address) {
  try { await ElMessageBox.confirm('删除这条本机示例地址？','删除地址',{confirmButtonText:'删除',cancelButtonText:'取消'}) } catch { return }
  await request.delete('/address/'+address.id)
  await load()
}
function startReview(order) { if(order.status!=='待评价')return ElMessage.warning('仅可评价待评价的示例订单'); reviewForm.value={...order,rate:order.rate||5,reviewText:(order.comment||'').replace(/<[^>]*>/g,'')}; reviewOpen.value=true }
async function saveReview() {
  const form=reviewForm.value
  if (!form.reviewText.trim()) return ElMessage.warning('请填写评价')
  const text=form.reviewText.replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))
  const r=await request.post('/orders',{...form,status:'已完成',comment:'<p>'+text+'</p>'})
  if(r.code!=='200')return ElMessage.error(r.msg)
  reviewOpen.value=false;await load();ElMessage.success('示例评价已保存')
}
async function saveProfile() {
  if(!profileForm.value.nickname?.trim())return ElMessage.warning('请输入昵称')
  const r=await request.post('/user',{...profileForm.value,id:acc.value.id})
  if(r.code!=='200')return ElMessage.error(r.msg)
  acc.value={...acc.value,...r.data,role:'ROLE_USER'}
  sessionStorage.setItem('account',JSON.stringify(acc.value))
  ElMessage.success(remoteMode.value?'服务端显示资料已保存':'本机资料已保存');go(remoteMode.value?'support':'mine')
}
async function changeAvatar(event) {
  const file=event.target.files?.[0]
  if(!file)return
  try{profileForm.value.avatarUrl=await readLocalImage(file)}catch(e){ElMessage.error(e.message)}
}
</script>
<template>
<div class="native-stage">
<div class="native-device">
<main class="native-app" :class="'view-'+view" :data-customer-view="view">
<div v-if="view==='home'||view==='mine'" class="mall-app-brand"><img src="/brand/dining-logo-v5.svg" alt="鲜食好店标志"/><strong>鲜食好店</strong><span>好好吃饭 · 轻松生活</span></div>
<header v-if="view!=='home' && view!=='mine'" class="native-header">
<button aria-label="返回" @click="backMobile">
<ArrowLeft/>
</button>
<strong class="page-brand-title"><img class="app-brand-icon" src="/brand/dining-logo-v5.svg" alt="鲜食好店标志"/>{{title}}</strong>
<button aria-label="回到首页" @click="go('home')">
<House/>
</button>
</header>
<template v-if="backendUnsupported"><section class="native-card"><h3>此页面尚未接入Java服务</h3><p class="support-disclaimer">当前后端模式覆盖客服、留言、附件、资料和显示配置。商城与先吃后付本机体验保留；不会把本地订单或额度当作服务端记录。</p><button class="primary-pill full" @click="go('support')">回服务中心</button><button class="primary-pill full" @click="returnDemo">切回本机商城体验</button></section></template>
<template v-else-if="view==='home'">
<div class="home-top">
<div class="store-location">
<Location/>
<strong>{{shops[0]?.nickname || '鲜食好店'}}</strong>
<span>示例门店</span>
</div>
<button @click="login">
<User/>
</button>
</div>
<div class="native-search" @click="go('category')">
<Search/>
<span>搜索想吃的餐品</span>
<small>好吃 · 新鲜</small>
</div>
<div v-if="notices.length" class="native-notice">
<span>公告</span>
<span>{{notices.map(n=>n.name).join(' · ')}}</span>
</div>
<div class="home-feature-grid">
<el-carousel v-if="banners.length" class="native-banner-carousel" height="var(--customer-hero-height,198px)" :interval="5000" arrow="hover">
  <el-carousel-item v-for="banner in banners" :key="banner.id">
    <div class="food-hero" @click="banner.goodsId ? go('detail',banner.goodsId) : go('category')">
      <img :src="banner.img" :alt="banner.name"/>
      <div>
<span class="hero-eyebrow">{{banner.info}}</span>
<h2>{{banner.name}}</h2>
<b>查看餐品 <ArrowRight/>
</b>
</div>
    </div>
  </el-carousel-item>
</el-carousel>
<div class="native-shortcuts">
<button v-for="(c,i) in categories.slice(0,4)" :key="c.id" @click="category=c.id;go('category')">
<span :class="'shortcut-'+i">
<component :is="[Shop,Collection,Clock,Wallet][i]"/>
</span>{{c.name}}</button>
<button @click="go('bill')">
<span class="shortcut-credit">
<Wallet/>
</span>先吃后付</button>
<button @click="go('orders')">
<span class="shortcut-1">
<Document/>
</span>我的订单</button>
<button @click="showFavorites">
<span class="shortcut-2">
<Star/>
</span>我的收藏</button>
<button @click="go('shops')">
<span class="shortcut-3">
<Shop/>
</span>全部商家</button>
</div>
</div>
<div class="credit-strip" @click="go('bill')">
<div>
<strong>先吃后付</strong>
<span>原演示参数：总额度¥{{money(creditRule.quotaMinor/100)}} · 单笔¥{{money(creditRule.transactionMaxMinor/100)}}</span>
</div>
<button>查看 <ArrowRight/>
</button>
</div>
<section class="native-card merchant-entry" @click="go('shops')"><Shop/><div><strong>入驻商家</strong><small>{{shops.length}}家示例商家 · 查看店铺与评价</small></div><ArrowRight/></section>
<div class="native-section-head">
<h3>为你推荐</h3>
<span @click="go('category')">全部餐品 <ArrowRight/>
</span>
</div>
<div class="food-grid">
<article v-for="g in products" :key="g.id" class="food-card" @click="go('detail',g.id)">
<img :src="g.img" :alt="g.name"/>
<div class="food-card-body">
<h4>{{g.name}}</h4>
<p>{{g.info}}</p>
<div class="food-tags">
<span>{{categories.find(t=>t.id===g.typeId)?.name}}</span>
<span>新鲜现做</span>
</div>
<div class="food-price-row">
<strong>
<small>¥</small>{{money(g.price)}}</strong>
<button :aria-label="'加入购物车 '+g.name" @click.stop="add(g)">
<Plus/>
</button>
</div>
</div>
</article>
</div>
</template>
<template v-else-if="view==='category'">
<div class="native-search">
<Search/>
<input v-model="keyword" placeholder="请输入餐品名称"/>
<button v-if="keyword" @click="keyword=''">清除</button>
</div>
<div class="catalog-tabs">
<button :class="{active:!category}" @click="category=''">全部</button>
<button v-for="c in categories" :key="c.id" :class="{active:category===c.id}" @click="category=c.id">{{c.name}}</button>
</div>
<div class="sort-tabs">
<button :class="{active:sort==='all'}" @click="sort='all'">综合推荐</button>
<button :class="{active:sort==='sales'}" @click="sort='sales'">销量</button>
<button :class="{active:sort==='new'}" @click="sort='new'">新品</button>
<span>{{filtered.length}}款餐品</span>
</div>
<div class="native-list">
<article v-for="g in filtered" :key="g.id" class="native-product-row" @click="go('detail',g.id)">
<img :src="g.img" :alt="g.name"/>
<div>
<h4>{{g.name}}</h4>
<p>{{g.info}}</p>
<small>示例销量 {{g.sales}}</small>
<div class="food-price-row">
<strong>¥{{money(g.price)}}</strong>
<button @click.stop="add(g)">选购</button>
</div>
</div>
</article>
<div v-if="!filtered.length" class="native-empty">没有找到餐品，试试其他关键词</div>
</div>
</template>
<template v-else-if="view==='detail' && detail">
<div class="detail-intro"><div class="detail-gallery">
<div class="detail-photo">
<img :src="mainPhoto" :alt="detail.name"/>
</div>
<div class="detail-thumbnails">
<button v-for="(img,index) in images" :key="img" :class="{active:mainPhoto===img}" @click="mainPhoto=img" :aria-label="'查看餐品图片 '+(index+1)">
<img :src="img" :alt="detail.name+' 图片'+(index+1)"/>
</button>
</div>
</div><div class="detail-summary">
<section class="native-card detail-info">
<div class="food-price-row">
<strong>¥{{money(detail.price)}}</strong>
<small>示例销量 {{detail.sales}} · 库存 {{detail.inventory}}</small>
</div>
<h2>{{detail.name}}</h2>
<p>{{detail.info}}</p>
<div class="food-tags">
<span>{{categories.find(c=>c.id===detail.typeId)?.name}}</span>
<span>单位：{{detail.unit}}</span>
</div>
</section>
<section class="native-card native-line" @click="specOpen=true">
<span>选择</span>
<strong>标准份 · {{quantity}}份</strong>
<ArrowRight/>
</section>
<section v-if="shop" class="native-card shop-summary" @click="go('shop',shop.id)">
<img :src="shop.avatarUrl||'/avatar.svg'" alt="店铺头像"/>
<div>
<h3>{{shop.nickname}}</h3>
<p>{{shop.info}}</p>
</div>
<ArrowRight/>
</section>
</div></div>
<section class="native-card">
<h3 class="accent-title">餐品详情</h3>
<div class="native-rich-content" v-html="sanitizeHTML(detail.content)">
</div>
<p class="muted">资料为界面示例。下单不会实际配送。</p>
</section>
<section class="native-card">
<h3 class="accent-title">顾客评价（{{comments.length}}）</h3>
<article v-for="c in comments" :key="c.id" class="native-review">
<div>
<img :src="users.find(u=>u.id===c.userId)?.avatarUrl||'/avatar.svg'" alt="用户头像"/>
<strong>{{users.find(u=>u.id===c.userId)?.nickname||'示例用户'}} · 示例评价</strong>
<span>{{'★'.repeat(c.rate||0)}}</span>
</div>
<div class="native-rich-content" v-html="sanitizeHTML(c.comment)">
</div>
<p v-if="c.reply" class="review-reply">商家回复：<span v-html="sanitizeHTML(c.reply)">
</span>
</p>
</article>
<p v-if="!comments.length" class="muted">暂无评价</p>
</section>
<div class="native-detail-bottom">
<button @click="favorite">
<Star/>
<small>{{detail.isCollected?'已收藏':'收藏'}}</small>
</button>
<button @click="go('cart')">
<ShoppingCart/>
<small>购物车</small>
</button>
<button class="soft-btn" @click="add(detail,quantity)">加入购物车</button>
<button class="primary-pill" @click="buy">立即下单</button>
</div>
</template>
<template v-else-if="view==='cart'">
<div class="native-list-heading">共{{cart.length}}件餐品 <span>本机购物车</span>
</div>
<div v-if="!cart.length" class="native-empty">
<ShoppingCart/>
<h3>购物车还是空的</h3>
<p>把喜欢的餐品放进来吧</p>
<button class="primary-pill" @click="go('category')">去逛逛</button>
</div>
<div class="native-list">
<article v-for="c in cart" :key="c.id" class="cart-row native-card">
<input type="checkbox" :value="c.id" v-model="selected" :disabled="c.unavailable" aria-label="选择餐品"/>
<img :src="c.goodsImg" :alt="c.goodsName" @click="go('detail',c.goodsId)"/>
<div>
<h4>{{c.goodsName}}</h4><button class="cart-remove" @click="qty(c,0)" :aria-label="'删除 '+c.goodsName">删除</button>
<p>{{c.unavailable?'餐品已移除，请删除':'标准份'}}</p>
<div class="food-price-row">
<strong>¥{{money(c.goodsPrice)}}</strong>
<div class="quantity">
<button :aria-label="'减少 '+c.goodsName" @click="qty(c,c.num-1)">
<Minus/>
</button>
<span>{{c.num}}</span>
<button :aria-label="'增加 '+c.goodsName" :disabled="c.unavailable" @click="qty(c,c.num+1)">
<Plus/>
</button>
</div>
</div>
</div>
</article>
</div>
<div v-if="cart.length" class="native-checkout-bar">
<label>
<input type="checkbox" :checked="availableCart.length>0 && selected.length===availableCart.length" @change="selected=selected.length===availableCart.length?[]:availableCart.map(c=>c.id)"/>全选</label>
<span>合计 <strong>¥{{money(total)}}</strong>
</span>
<button class="primary-pill" @click="checkout">去结算 ({{selectedCart.length}})</button>
</div>
</template>
<template v-else-if="view==='checkout'">
<section class="native-card address-summary" @click="router.push('/mall/address?select=1')">
<Location/>
<div>
<small>示例收货地址 · 点击选择</small>
<h4>{{activeAddress?.address||'请设置示例地址'}}</h4>
<p>{{activeAddress?.name}} {{activeAddress?.phone}}</p>
</div>
<ArrowRight/>
</section>
<section class="native-card">
<article v-for="c in selectedCart" :key="c.id" class="native-product-row compact">
<img :src="c.goodsImg" :alt="c.goodsName"/>
<div>
<h4>{{c.goodsName}}</h4><button class="cart-remove" @click="qty(c,0)" :aria-label="'删除 '+c.goodsName">删除</button>
<p>标准份</p>
<span>¥{{money(c.goodsPrice)}} × {{c.num}}</span>
</div>
</article>
<div class="native-line">
<span>订单备注</span>
<input v-model="note" placeholder="口味、餐具等（本机示例）"/>
</div>
</section>
<section class="native-card">
<h3>支付方式</h3>
<label class="payment-option" :class="{chosen:payMode==='later'}">
<span class="credit-icon">
<Wallet/>
</span>
<div>
<strong>先吃后付</strong>
<small>先提交请求，商家发布后由你确认预占</small>
</div>
<input type="radio" value="later" v-model="payMode"/>
</label>
<label class="payment-option">
<span class="credit-icon muted">
<Wallet/>
</span>
<div>
<strong>普通结算</strong>
<small>支付未接入，订单保持待支付</small>
</div>
<input type="radio" value="now" v-model="payMode"/>
</label>
</section>
<section class="native-card">
<div class="native-line">
<span>商品金额</span>
<b>¥{{money(total)}}</b>
</div>
<div class="native-line">
<span>配送费</span>
<b>¥0.00</b>
</div>
<div class="native-line">
<span>合计</span>
<strong class="red">¥{{money(total)}}</strong>
</div>
</section>
<section v-if="payMode==='later' && exceedsCreditLimit" class="native-card"><h3 class="red">超过原演示单笔上限</h3><p>同一商家本次金额超过 ¥{{money(creditRule.transactionMaxMinor/100)}}。你可以调整餐品或使用普通结算；平台可按原申请/复核流程调整演示参数。</p><button class="credit-text-button" @click="go('detail',700001)">查看20元体验套餐</button></section>
<p class="checkout-disclaimer">界面体验：不会扣款、授信或发货。示例订单仅保存在本浏览器。</p>
<div class="native-checkout-bar">
<span>合计 <strong>¥{{money(total)}}</strong>
</span>
<button class="primary-pill" :disabled="!selectedCart.length || submitting || (payMode==='later' && exceedsCreditLimit)" @click="submit">{{submitting?'正在保存':payMode==='later'?'提交用餐请求':'提交示例订单'}}</button>
</div>
</template>
<template v-else-if="view==='orders'">
<div class="catalog-tabs">
<button v-for="t in orderTabs" :key="t" :class="{active:tab===t}" @click="tab=t">{{t==='所有订单'?'全部':t}}</button>
</div>
<div class="customer-order-grid">
<section v-for="o in visibleOrders" :key="o.id" class="native-card order-card">
<div class="order-top">
<span>订单号：{{o.no}}</span>
<b>{{o.status}}</b>
</div>
<article class="native-product-row compact" @click="go('detail',o.goodsId)">
<img :src="o.img" :alt="o.name"/>
<div>
<h4>{{o.name}}</h4>
<p>标准份</p>
<span>¥{{money(o.price/o.num)}} × {{o.num}}</span>
</div>
</article>
<div class="order-total">共{{o.num}}件餐品，总金额：<b>¥{{money(o.price)}}</b>
</div>
<div class="order-actions"><button v-if="o.afterSalesCount" @click="go('afterSales')">售后进度（{{o.afterSalesCount}}）</button>
<button v-if="o.status==='待支付'" @click="orderAction(o,'cancel')">取消订单</button>
<button v-if="o.status==='待支付'" class="primary-pill" @click="orderAction(o,'pay')">支付未接入</button>
<button v-if="o.status==='待收货'" @click="orderAction(o,'delivery')">确认收货（示例）</button>
<button v-if="o.canReview" class="primary-pill" @click="go('orderService',o.id)">去评价</button><button v-else-if="o.reviewed" @click="go('orderService',o.id)">查看评价</button>
<button v-if="o.canAfterSales" @click="go('orderService',o.id)">售后 / 退款</button>
<button @click="o.creditOrderId?go('deferredOrder',o.creditOrderId):o.creditRequestId?go('bill'):go('orderDetail',o.id)">订单详情</button>
</div>
</section>
</div>
<div v-if="!visibleOrders.length" class="native-empty">暂无该状态的示例订单</div>
</template>
<template v-else-if="view==='mine'">
<div class="mine-banner">
<img :src="acc.avatarUrl||'/avatar.svg'" alt="用户头像"/>
<div>
<h2>{{acc.nickname||'游客'}}</h2>
<p>{{acc.id?'欢迎回来，今天也好好吃饭':'111 / 111 登录体验'}}</p>
</div>
<button aria-label="切换身份" @click="login">
<Setting/>
</button>
</div>
<section class="native-card mine-orders">
<button v-for="(s,i) in ['待支付','待收货','待评价','售后/退款','所有订单']" :key="s" @click="s==='售后/退款'?go('afterSales'):(tab=s,go('orders'))">
<component :is="[Wallet,Shop,ChatDotRound,Collection,Document][i]"/>
<span>{{s==='所有订单'?'全部订单':s}}</span>
</button>
</section>
<section class="native-card mine-credit" @click="go('bill')">
<div>
<small>先吃后付 · 示例额度</small>
<strong>¥{{money((creditData?.account?.availableMinor||0)/100)}}</strong>
<span>可用 / 预占 / 应还，同源业务记录</span>
</div>
<button>查看账单 <ArrowRight/>
</button>
</section>
<section class="native-card service-grid">
<button @click="showFavorites">
<Star/>
<span>我的收藏</span>
</button>
<button @click="go('address')">
<Location/>
<span>收货地址</span>
</button>
<button @click="go('bill')">
<Wallet/>
<span>先吃后付</span>
</button>
<button @click="go('orders')">
<Document/>
<span>我的订单</span>
</button>
<button @click="go('profile')">
<User/>
<span>个人资料</span>
</button>
<button @click="go('category')">
<Shop/>
<span>发现餐品</span>
</button>
<button @click="go('afterSales')"><Document/><span>售后进度</span></button><button @click="go('support')"><ChatDotRound/><span>客户服务</span></button><button @click="login">
<SwitchButton/>
<span>切换身份</span>
</button>
</section>
</template>
<template v-else-if="view==='orderService'"><PurchaseFeedback :order-id="route.query.id" :credit-order-id="route.query.creditId" @updated="load" /></template>
<template v-else-if="view==='afterSales' || view==='afterSale'"><AfterSalesCustomer :case-id="view==='afterSale'?String(route.query.id||''):undefined" /></template>
<template v-else-if="view==='bill' || view==='deferredOrder'"><CreditCustomer :mode="view" :order-id="String(route.query.id||'')" /></template>
<template v-else-if="view==='address'">
  <section v-for="address in addresses" :key="address.id" class="native-card">
    <div class="address-summary" @click="chooseAddress(address)">
<Location/>
<div>
<h4>{{address.name}} {{address.phone}}</h4>
<p>{{address.address}}</p>
<span v-if="addressId===address.id" class="default-address">已选地址</span>
</div>
<ArrowRight/>
</div>
    <div class="address-actions">
<button @click="chooseAddress(address)">{{route.query.select?'选择此地址':'设为默认'}}</button>
<button @click="editAddress(address)">
<Edit/>编辑</button>
<button @click="deleteAddress(address)">
<Delete/>删除</button>
</div>
  </section>
  <p v-if="!addresses.length" class="native-empty">还没有地址，请添加示例资料</p>
  <button class="primary-pill address-button" @click="editAddress()">新增示例地址</button>
</template>
<template v-else-if="view==='profile'">
  <section class="native-card profile-editor">
<label class="profile-avatar">
<img :src="profileForm.avatarUrl||'/avatar.svg'" alt="头像"/>
<span>{{remoteMode?'服务端暂用默认头像':'更换头像'}}</span>
<input v-if="!remoteMode" type="file" accept="image/png,image/jpeg,image/webp" @change="changeAvatar"/>
</label>
<label>{{remoteMode?'服务端账号（只读）':'体验账号'}}<input :value="profileForm.username||'111'" disabled/>
</label>
<label>昵称<input v-model="profileForm.nickname" placeholder="请输入昵称" maxlength="30"/>
</label>
<label>邮箱（示例）<input v-model="profileForm.email" placeholder="demo@example.invalid"/>
</label>
<label>电话（示例）<input v-model="profileForm.phone" placeholder="00000000000"/>
</label>
<p class="muted">{{remoteMode?'这里只修改显示资料，登录身份和密码保持服务端控制':'资料仅保存在当前浏览器，建议不要填写真实个人信息'}}</p>
<button class="primary-pill full" @click="saveProfile">{{remoteMode?'保存服务端资料':'保存本机资料'}}</button>
</section>
</template>
<template v-else-if="view==='shops'">
  <div class="native-list-heading">入驻商家<span>{{shops.length}}家 · 示例资料</span></div>
  <div class="customer-shop-grid"><section v-for="store in shops" :key="store.id" class="native-card shop-summary" @click="go('shop',store.id)"><img :src="store.avatarUrl||'/avatar.svg'" alt="店铺头像"/><div><h3>{{store.nickname}}</h3><p>{{store.info}}</p><p>{{products.filter(g=>g.unitId===store.id).length}}款在售餐品</p></div><ArrowRight/></section>
  </div><p v-if="!shops.length" class="native-empty">暂无入驻商家</p>
</template>
<template v-else-if="view==='shop'">
  <section v-if="shop" class="native-card shop-summary">
<img :src="shop.avatarUrl||'/avatar.svg'" alt="店铺头像"/>
<div>
<h3>{{shop.nickname}}</h3>
<p>{{shop.info}}</p>
<p>{{shop.address}}</p>
</div>
</section>
  <div class="native-section-head">
<h3>店铺餐品</h3>
<span>{{filtered.length}}款</span>
</div>
<div class="native-list">
<article v-for="g in filtered" :key="g.id" class="native-product-row" @click="go('detail',g.id)">
<img :src="g.img" :alt="g.name"/>
<div>
<h4>{{g.name}}</h4>
<p>{{g.info}}</p>
<div class="food-price-row">
<strong>¥{{money(g.price)}}</strong>
<button @click.stop="add(g)">选购</button>
</div>
</div>
</article>
</div>
<section v-if="shop" class="native-card"><button class="native-line full" @click="router.push({path:'/mall/support',query:{unitId:shop.id}})"><ChatDotRound/><span>联系本店客服</span><ArrowRight/></button><button class="native-line full" @click="router.push({path:'/mall/supportNew',query:{unitId:shop.id}})"><Document/><span>给本店留言</span><ArrowRight/></button><h3 class="accent-title">店铺信息</h3><div class="native-line"><span>商家名称</span><strong>{{shop.nickname}}</strong></div><div class="native-line"><span>门店地址</span><span>{{shop.address||'尚未填写'}}</span></div><div class="native-line"><span>联系电话</span><span>{{shop.phone||'尚未填写'}}</span></div></section>
<section class="native-card"><h3 class="accent-title">店铺评价（{{shopComments.length}}）</h3><article v-for="comment in shopComments" :key="comment.id" class="native-review"><div><img :src="users.find(u=>u.id===comment.userId)?.avatarUrl||'/avatar.svg'" alt="头像"/><strong>{{users.find(u=>u.id===comment.userId)?.nickname||'示例用户'}}</strong><span>{{'★'.repeat(comment.rate||0)}}</span></div><small class="muted">{{comment.name}} · 示例订单评价</small><div class="native-rich-content" v-html="sanitizeHTML(comment.comment)"></div><p v-if="comment.reply" class="review-reply">商家回复：<span v-html="sanitizeHTML(comment.reply)"></span></p></article><p v-if="!shopComments.length" class="muted">暂无示例评价</p></section>
</template>
<template v-else-if="view==='orderDetail' && currentOrder">
  <section class="native-card">
<h3>{{currentOrder.status}}</h3>
<p>订单号：{{currentOrder.no}}</p>
<p>下单时间：{{currentOrder.time}}</p>
<p>结算选择：{{currentOrder.paymentMode==='later'?'先吃后付（UI示例）':'普通结算（未接支付）'}}</p>
</section>
  <section class="native-card address-summary">
<Location/>
<div>
<h4>{{currentOrder.userName}} {{currentOrder.userPhone}}</h4>
<p>{{currentOrder.userAddress}}</p>
</div>
</section>
  <section class="native-card">
<article class="native-product-row compact" @click="go('detail',currentOrder.goodsId)">
<img :src="currentOrder.img" :alt="currentOrder.name"/>
<div>
<h4>{{currentOrder.name}}</h4>
<p>标准份 × {{currentOrder.num}}</p>
<strong>¥{{money(currentOrder.price)}}</strong>
</div>
</article>
<div class="native-line">
<span>订单备注</span>
<span>{{currentOrder.remark||'无'}}</span>
</div>
</section>
  <section class="native-card"><button class="native-line full" data-testid="order-contact-shop" @click="router.push({path:'/mall/support',query:{unitId:currentOrder.unitId,orderId:currentOrder.id}})"><ChatDotRound/><span>联系本店客服</span><ArrowRight/></button><button class="native-line full" @click="router.push({path:'/mall/supportNew',query:{unitId:currentOrder.unitId,orderId:currentOrder.id}})"><Document/><span>向本店咨询此订单</span><ArrowRight/></button></section>
  <PurchaseFeedback :order-id="currentOrder.id" @updated="load" />
<p class="checkout-disclaimer">该订单为本机界面示例，不会实际付款或配送。</p>
</template>
<template v-else-if="view==='detail' || view==='orderDetail'">
<div class="native-empty">记录不存在或已移除<button class="primary-pill full" @click="go('home')">返回商城</button>
</div>
</template>
<template v-else-if="['manuals','manual','materials','updates'].includes(view)"><HelpCustomer :mode="view" :resource-id="String(route.query.id||'')"/></template>
<template v-else-if="['support','supportChat','supportTickets','supportNew','supportTicket'].includes(view)"><SupportCustomer :mode="view" /></template>
<template v-else-if="view==='notFound'"><div class="native-empty"><h3>这个页面暂不可用</h3><p>请从商城导航继续浏览</p><button class="primary-pill" @click="go('home')">返回首页</button></div></template>
<footer v-if="['home','category','cart','mine'].includes(view)" class="native-tabbar">
<button v-for="(v,i) in ['home','category','cart','mine']" :key="v" :class="{active:view===v}" @click="go(v)">
<component :is="[House,Grid,ShoppingCart,User][i]"/>
<span>{{['首页','分类','购物车','我的'][i]}}</span>
<b v-if="v==='cart'&&cart.length">{{cart.reduce((s,c)=>s+c.num,0)}}</b>
</button>
</footer>
<div v-if="specOpen" class="native-overlay" @click.self="specOpen=false">
<section class="native-sheet">
<button class="sheet-close" @click="specOpen=false">×</button>
<h3>{{detail?.name}}</h3>
<p>规格</p>
<span class="spec-selected">标准份</span>
<div class="native-line">
<span>数量</span>
<div class="quantity">
<button @click="quantity=Math.max(1,quantity-1)">
<Minus/>
</button>
<span>{{quantity}}</span>
<button @click="quantity=Math.min(detail.inventory,quantity+1)">
<Plus/>
</button>
</div>
</div>
<button class="primary-pill full" @click="specOpen=false">确定</button>
</section>
</div>
<div v-if="addressOpen" class="native-overlay" @click.self="addressOpen=false">
<section class="native-sheet">
<button class="sheet-close" @click="addressOpen=false">×</button>
<h3>{{addressForm.id?'编辑示例地址':'新增示例地址'}}</h3>
<form class="native-form" @submit.prevent="saveAddress">
<label>收货人<input v-model="addressForm.name" placeholder="示例收货人" maxlength="30" required/>
</label>
<label>联系电话<input v-model="addressForm.phone" placeholder="00000000000" maxlength="30" required/>
</label>
<label>详细地址<textarea v-model="addressForm.address" placeholder="请使用示例地址" maxlength="200" required>
</textarea>
</label>
<button class="primary-pill full" type="submit">保存地址</button>
</form>
</section>
</div>
<div v-if="reviewOpen" class="native-overlay" @click.self="reviewOpen=false">
<section class="native-sheet">
<button class="sheet-close" @click="reviewOpen=false">×</button>
<h3>评价餐品</h3><p>仅可评价待评价的示例订单，不会发布真实消费评价</p>
<p>{{reviewForm.name}}</p>
<el-rate v-model="reviewForm.rate"/>
<textarea class="review-input" v-model="reviewForm.reviewText" placeholder="填写本机示例评价" maxlength="500">
</textarea>
<button class="primary-pill full" @click="saveReview">提交评价</button>
</section>
</div>
</main>
</div>
</div>
</template>
<style src="../style/native-mall.css">
</style>
