<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Picture, Refresh, Search, Tickets } from '@element-plus/icons-vue'
import request from '@/utils/request'
import {mergeMessages,refreshHistory} from '@/utils/service-history'
import ServiceUpload from '@/components/ServiceUpload.vue'
import ServiceAttachment from '@/components/ServiceAttachment.vue'
import {isBackendMode} from '@/utils/service-mode'

const STORAGE_KEY = 'mall-ui-data-v2'
const IMAGE_LIMIT = 250 * 1024
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif']
const STATUS = { OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }
const NEXT_STATUS = { OPEN: 'IN_PROGRESS', IN_PROGRESS: 'RESOLVED', RESOLVED: 'CLOSED' }
const STATUS_ACTION = { OPEN: '开始处理', IN_PROGRESS: '标记已解决', RESOLVED: '关闭留言' }

function currentActor() {
  try { return JSON.parse(sessionStorage.getItem('account') || '{}') || {} } catch { return {} }
}
const identity = value => `${value.role || ''}:${value.id ?? ''}`
const actor = ref(currentActor())
const merchant = computed(() => actor.value.role === 'ROLE_UNIT')
const allowed = computed(() => ['ROLE_UNIT', 'ROLE_ADMIN'].includes(actor.value.role))
const actorLabel = computed(() => merchant.value ? '本店商家' : '平台客服')
const actorName = computed(() => actor.value.nickname || actor.value.name || actor.value.username || actorLabel.value)
const tab = ref('conversations')
const sessions = ref([])
const tickets = ref([])
const counts = ref({})
const overviewLoading = ref(false)
const overviewError = ref('')
const lastSynced = ref(null)
const sessionSearch = ref('')
const selectedSessionId = ref('')
const selectedSession = ref(null)
const messages = ref([])
const messageAttachments=ref([]),replyAttachments=ref([]),replyUploading=ref(false),sessionHasMore=ref(false),page=ref(1),pageSize=ref(20),ticketTotal=ref(0),sessionTotal=ref(0),includeArchived=ref(false),shopOptions=ref([])
let olderPages=0,readBlocked=false
const media=row=>row?.attachments?.length?row.attachments:row?.attachment?[row.attachment]:[]
const priorityLabel=value=>({LOW:'低',MEDIUM:'中',HIGH:'高',NORMAL:'中',IMPORTANT:'高'})[value]||value

const sessionLoading = ref(false)
const sessionError = ref('')
const messageText = ref('')
const messageAttachment = ref(null)
const attachmentLoading = ref(false)
const messageSending = ref(false)
const fileInput = ref(null)
const messageList = ref(null)
const ticketSearch = ref('')
const ticketStatus = ref('')
const ticketPriority = ref('')
const ticketShop = ref('')
const ticketOpen = ref(false)
const selectedTicketId = ref('')
const selectedTicket = ref(null)
const ticketLoading = ref(false)
const ticketError = ref('')
const ticketReply = ref('')
const ticketSaving = ref(false)
const imagePreview = ref(null)
const previewOpen = ref(false)
let overviewVersion = 0
let sessionVersion = 0
let ticketVersion = 0
let attachmentVersion = 0
let messageAttempt = null
let replyAttempt = null
let timer
let disposed = false
let refreshBusy = false
let refreshQueued = false

const sameId = (a, b) => String(a) === String(b)
const actorUnchanged = stamp => !disposed && stamp === identity(currentActor()) && stamp === identity(actor.value)
const errorText = error => error?.message || '读取失败，请稍后重试'
const dateTime = value => {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}
const shortTime = value => {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}
const statusType = value => ({ OPEN: 'warning', IN_PROGRESS: 'primary', RESOLVED: 'success', CLOSED: 'info' }[value] || 'info')
const customerName = row => row?.customerName || row?.userName || `顾客 ${row?.userId ?? ''}`
const shopName = row => row?.shopName || row?.unitName || `店铺 ${row?.unitId ?? ''}`
const initials = value => String(value || '顾客').slice(0, 1)
const unread = row => Math.max(0, Number(row?.unread ?? row?.unreadCount) || 0)
const messageSummary = message => typeof message === 'string' ? message : message?.text || (message?.attachment ? '[图片]' : '暂无消息')
const senderLabel = message => message?.senderLabel || ({ ROLE_ADMIN: '平台客服', ROLE_UNIT: '本店商家', ROLE_USER: '顾客' }[message?.senderRole] || '客服记录')
const isOwn = message => message?.senderRole === actor.value.role && sameId(message?.senderId, actor.value.id)
const safeImage = attachment => {
  const value = attachment?.dataUrl
  return typeof value === 'string' && value.length <= 350000 && /^data:image\/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/]+={0,2}$/.test(value) ? value : ''
}
const stats = computed(() => ({
  sessions: counts.value.sessions ?? sessions.value.length,
  unread: counts.value.unreadMessages ?? sessions.value.reduce((total, row) => total + unread(row), 0),
  active: (counts.value.openTickets ?? tickets.value.filter(row => row.status === 'OPEN').length) + (counts.value.inProgressTickets ?? tickets.value.filter(row => row.status === 'IN_PROGRESS').length),
  resolved: counts.value.resolvedTickets ?? tickets.value.filter(row => row.status === 'RESOLVED').length,
}))
const visibleSessions = computed(() => {
  const query = sessionSearch.value.trim().toLowerCase()
  return [...sessions.value].filter(row => !query || [customerName(row), shopName(row), row.id, messageSummary(row.lastMessage)].some(value => String(value).toLowerCase().includes(query)))
    .sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || '')))
})
const shops = computed(() => shopOptions.value.length?shopOptions.value:[...new Map(tickets.value.map(row => [String(row.unitId), { id: String(row.unitId), name: shopName(row) }])).values()])
const visibleTickets = computed(() => {
  const query = ticketSearch.value.trim().toLowerCase()
  return [...tickets.value].filter(row => (!ticketStatus.value || row.status === ticketStatus.value)
    && (!ticketPriority.value || row.priority === ticketPriority.value)
    && (!ticketShop.value || sameId(row.unitId, ticketShop.value))
    && (!query || [row.title, row.content, row.id, customerName(row), shopName(row)].some(value => String(value || '').toLowerCase().includes(query))))
    .sort((a, b) => String(b.updatedAt || b.createdAt || '').localeCompare(String(a.updatedAt || a.createdAt || '')))
})
const canSend = computed(() => selectedSession.value && !messageSending.value && !attachmentLoading.value && (messageText.value.trim() || messageAttachments.value.length))
const canReply = computed(() => selectedTicket.value && selectedTicket.value.canReply && ticketReply.value.trim() && !ticketSaving.value && !ticketLoading.value && !replyUploading.value)

async function dataFrom(promise) {
  const result = await promise
  if (String(result?.code) !== '200') throw new Error(result?.msg || '本机操作未完成')
  return result.data
}
function resetSession() {
  sessionVersion++
  attachmentVersion++
  selectedSessionId.value = ''
  selectedSession.value = null
  messages.value = []
  messageAttachments.value=[];sessionHasMore.value=false;olderPages=0;readBlocked=false
  messageText.value = ''
  messageAttachment.value = null
  attachmentLoading.value = false
  sessionLoading.value = false
  sessionError.value = ''
  messageAttempt = null
}
function syncActor() {
  const next = currentActor()
  if (identity(next) === identity(actor.value)) return
  actor.value = next
  overviewVersion++
  ticketVersion++
  resetSession()
  sessions.value = []
  tickets.value = []
  counts.value = {}
  ticketOpen.value = false
  selectedTicketId.value = ''
  selectedTicket.value = null
  ticketReply.value = ''
  replyAttachments.value=[]
  ticketError.value = ''
  overviewError.value = ''
  lastSynced.value = null
  previewOpen.value = false
}
async function loadOverview({ visible = false } = {}) {
  const version = ++overviewVersion
  const stamp = identity(actor.value)
  if (visible) overviewLoading.value = true
  try {
    const params={page:page.value,limit:pageSize.value,includeArchived:!merchant.value&&includeArchived.value,...(ticketStatus.value?{status:ticketStatus.value}:{}),...(ticketPriority.value?{priority:ticketPriority.value}:{}),...(ticketShop.value?{unitId:String(ticketShop.value)}:{}),...((tab.value==='tickets'?ticketSearch.value:sessionSearch.value).trim()?{q:(tab.value==='tickets'?ticketSearch.value:sessionSearch.value).trim()}:{})}
    const result = await dataFrom(request.get('/support/overview',{params}))
    if (version !== overviewVersion || !actorUnchanged(stamp)) return
    sessions.value = Array.isArray(result?.sessions) ? result.sessions : []
    tickets.value = Array.isArray(result?.tickets) ? result.tickets : []
    counts.value = result?.counts || {}
    ticketTotal.value=result.ticketTotal??tickets.value.length;sessionTotal.value=result.sessionTotal??sessions.value.length
    overviewError.value = ''
    lastSynced.value = new Date().toISOString()
    if (selectedSessionId.value && !sessions.value.some(row => sameId(row.id, selectedSessionId.value))) resetSession()
    if (selectedTicketId.value && !tickets.value.some(row => sameId(row.id, selectedTicketId.value))) {
      ticketVersion++
      selectedTicket.value = null
      ticketError.value = '这条留言已不可访问，请关闭详情后刷新列表'
    }
  } catch (error) {
    if (version === overviewVersion && actorUnchanged(stamp)) overviewError.value = errorText(error)
  } finally {
    if (version === overviewVersion) overviewLoading.value = false
  }
}
function atThreadBottom() {
  const node = messageList.value
  return !node || node.scrollHeight - node.scrollTop - node.clientHeight < 90
}
async function loadSession({ visible = false, markRead = false, scroll = false } = {}) {
  const id = selectedSessionId.value
  if (!id) return
  const version = ++sessionVersion
  const stamp = identity(actor.value)
  const current = () => version === sessionVersion && sameId(id, selectedSessionId.value) && actorUnchanged(stamp)
  const follow = scroll || atThreadBottom()
  if (visible) sessionLoading.value = true
  try {
    const result = await dataFrom(request.get('/support/session', { params: { id } }))
    if (!current()) return
    selectedSession.value = result.session
    const refreshed=await refreshHistory({prior:olderPages?messages.value:[],latest:result,current,fetchOlder:beforeId=>dataFrom(request.get('/support/session',{params:{id,beforeId}}))})
    if(!current())return
    if(refreshed.reset){olderPages=0;readBlocked=true}
    messages.value=refreshed.messages
    if(!olderPages)sessionHasMore.value=!!result.hasMore
    sessionError.value=refreshed.readable?'':'消息历史需要重新读取，请点击读取更早消息；未推进已读位置'
    if (follow) {
      await nextTick()
      if (current() && messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
    }
    if (refreshed.readable && !readBlocked && markRead && current() && tab.value === 'conversations' && document.visibilityState !== 'hidden' && unread(result.session) > 0 && messages.value.length) {
      await dataFrom(request.post('/support/mark-read', { sessionId: id,lastSeenMessageId:String(messages.value.at(-1).id),clientKey:newKey('support-read') }))
      if (!current()) return
      selectedSession.value = { ...selectedSession.value, unread: 0, unreadCount: 0 }
      await loadOverview()
    }
  } catch (error) {
    if (current()) {
      sessionError.value = errorText(error)
      selectedSession.value = null
      messages.value = []
    }
  } finally {
    if (current()) sessionLoading.value = false
  }
}
async function loadTicket({ visible = false } = {}) {
  const id = selectedTicketId.value
  if (!id || !ticketOpen.value) return
  const version = ++ticketVersion
  const stamp = identity(actor.value)
  const current = () => version === ticketVersion && sameId(id, selectedTicketId.value) && ticketOpen.value && actorUnchanged(stamp)
  if (visible) ticketLoading.value = true
  try {
    const result = await dataFrom(request.get('/support/ticket', { params: { id } }))
    if (!current()) return
    selectedTicket.value = result
    ticketError.value = ''
  } catch (error) {
    if (current()) {
      selectedTicket.value = null
      ticketError.value = errorText(error)
    }
  } finally {
    if (current()) ticketLoading.value = false
  }
}
async function refreshAll(visible = false) {
  syncActor()
  if (!allowed.value || disposed) return
  if (refreshBusy) { refreshQueued = true; return }
  refreshBusy = true
  try {
    await Promise.all([loadOverview({ visible }), loadSession({ markRead: true }), loadTicket()])
  } finally {
    refreshBusy = false
    if (refreshQueued && !disposed) { refreshQueued = false; void refreshAll() }
  }
}
function selectSession(row) {
  if (sameId(row.id, selectedSessionId.value)) return loadSession({ markRead: true })
  resetSession()
  selectedSessionId.value = row.id
  selectedSession.value = row
  void loadSession({ visible: true, markRead: true, scroll: true })
}
function openTicket(row) {
  ticketVersion++
  selectedTicketId.value = row.id
  selectedTicket.value = null
  ticketReply.value = ''
  replyAttachments.value=[]
  ticketError.value = ''
  replyAttempt = null
  ticketOpen.value = true
  void loadTicket({ visible: true })
}
function newKey(prefix) {
  return `${prefix}-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`
}
function removeAttachment(){messageAttachments.value=[];attachmentLoading.value=false;attachmentVersion++}
async function loadOlder(){const id=selectedSessionId.value,stamp=identity(actor.value),beforeId=String(messages.value[0]?.id||'');if(!beforeId||sessionLoading.value)return;sessionLoading.value=true;try{const r=await dataFrom(request.get('/support/session',{params:{id,beforeId}}));if(!sameId(id,selectedSessionId.value)||!actorUnchanged(stamp))return;const height=messageList.value?.scrollHeight||0,top=messageList.value?.scrollTop||0;messages.value=mergeMessages(r.messages,messages.value);sessionHasMore.value=!!r.hasMore;if(!r.hasMore)readBlocked=false;olderPages++;await nextTick();if(messageList.value)messageList.value.scrollTop=top+messageList.value.scrollHeight-height}catch(e){if(actorUnchanged(stamp))ElMessage.error(errorText(e))}finally{if(actorUnchanged(stamp))sessionLoading.value=false}}
async function sendMessage() {
  if (!canSend.value) return
  syncActor()
  if (!allowed.value || !selectedSession.value) return
  const id = selectedSessionId.value
  const stamp = identity(actor.value)
  const text = messageText.value.trim()
  const attachmentIds=messageAttachments.value.map(a=>String(a.id))
  const signature = JSON.stringify([stamp, id, text, attachmentIds])
  if (messageAttempt?.signature !== signature) messageAttempt = { signature, clientKey: newKey('support-message') }
  messageSending.value = true
  try {
    await dataFrom(request.post('/support/send-message', { sessionId: id, text, attachmentIds, clientKey: messageAttempt.clientKey }))
    if (!actorUnchanged(stamp)) return
    if (sameId(id, selectedSessionId.value)) {
      messageText.value = ''
      removeAttachment()
      messageAttempt = null
      await loadSession({ markRead: true, scroll: true })
    }
    await loadOverview()
    ElMessage.success(isBackendMode()?'回复已保存到Java模拟服务':'回复已保存到本机会话')
  } catch (error) {
    if (actorUnchanged(stamp)) ElMessage.error(errorText(error))
  } finally { messageSending.value = false }
}
async function replyTicket() {
  if (!canReply.value) return
  syncActor()
  if (!allowed.value || !selectedTicket.value) return
  const id = selectedTicketId.value
  const stamp = identity(actor.value)
  const text = ticketReply.value.trim()
  const signature = JSON.stringify([stamp, id, text,selectedTicket.value.version,replyAttachments.value.map(a=>a.id)])
  if (replyAttempt?.signature !== signature) replyAttempt = { signature, clientKey: newKey('support-reply') }
  ticketSaving.value = true
  try {
    await dataFrom(request.post('/support/reply-ticket', { ticketId: id, text,version:selectedTicket.value.version,attachmentIds:replyAttachments.value.map(a=>String(a.id)), clientKey: replyAttempt.clientKey }))
    if (!actorUnchanged(stamp)) return
    if (sameId(id, selectedTicketId.value)) {
      ticketReply.value = ''
      replyAttachments.value=[]
      replyAttempt = null
      await loadTicket()
    }
    await loadOverview()
    ElMessage.success('回复已追加，之前的沟通记录已保留')
  } catch (error) {
    if (actorUnchanged(stamp)) {
      ElMessage.error(errorText(error))
      await loadTicket()
    }
  } finally { ticketSaving.value = false }
}
async function advanceTicket(wanted) {
  const row = selectedTicket.value
  const status = typeof wanted==='string'?wanted:NEXT_STATUS[row?.status]
  if (!row || row.archived || !status || ticketSaving.value || ticketLoading.value) return
  syncActor()
  if (!allowed.value || !selectedTicket.value) return
  const stamp = identity(actor.value)
  ticketSaving.value = true
  try {
    await dataFrom(request.post('/support/ticket-status', { ticketId: row.id, status, version: row.version, clientKey: `support-status-${stamp}-${row.id}-${row.version}-${status}` }))
    if (!actorUnchanged(stamp)) return
    await Promise.all([loadTicket(), loadOverview()])
    ElMessage.success(`留言已更新为“${STATUS[status]}”`)
  } catch (error) {
    if (actorUnchanged(stamp)) {
      ElMessage.error(errorText(error))
      await Promise.all([loadTicket(), loadOverview()])
    }
  } finally { ticketSaving.value = false }
}
async function archiveTicket(restore=false){const row=selectedTicket.value;if(!row||merchant.value||ticketSaving.value)return;const stamp=identity(actor.value);ticketSaving.value=true;try{await dataFrom(request.post('/support/'+(restore?'restore-ticket':'archive-ticket'),{ticketId:row.id,version:row.version,reason:restore?'平台恢复留言':'平台将留言移入可恢复归档',clientKey:newKey('support-archive')}));if(!actorUnchanged(stamp))return;await Promise.all([loadTicket(),loadOverview()]);ElMessage.success(restore?'留言已恢复':'留言已归档，历史记录保留')}catch(e){if(actorUnchanged(stamp))ElMessage.error(errorText(e))}finally{ticketSaving.value=false}}
function showImage(attachment) {
  if (!safeImage(attachment)) return
  imagePreview.value = attachment
  previewOpen.value = true
}
function resetFilters() {
  ticketSearch.value = ''
  ticketStatus.value = ''
  ticketPriority.value = ''
  ticketShop.value = '';includeArchived.value=false;page.value=1
}
const onStorage = event => { if (event.key === STORAGE_KEY || event.key === null) void refreshAll() }
const onFocus = () => void refreshAll()
const onVisibility = () => { if (document.visibilityState !== 'hidden') void refreshAll() }
watch([ticketSearch,ticketStatus,ticketPriority,ticketShop,includeArchived,pageSize,sessionSearch],()=>{page.value=1;void loadOverview()})
watch(page,()=>void loadOverview())
watch(tab, value => {page.value=1;void loadOverview(); if (value === 'conversations') void loadSession({ markRead: true }) })
watch(ticketOpen, value => {
  if (!value) {
    ticketVersion++
    selectedTicketId.value = ''
    selectedTicket.value = null
    ticketLoading.value = false
    ticketReply.value = ''
    ticketError.value = ''
    replyAttempt = null
  }
})
onMounted(() => {
  request.get('/unit').then(r=>{if(!disposed&&r.code==='200')shopOptions.value=r.data.map(s=>({id:String(s.id),name:s.nickname||s.name}))})
  void refreshAll(true)
  timer = window.setInterval(() => { if (document.visibilityState !== 'hidden') void refreshAll() }, 5000)
  window.addEventListener('storage', onStorage)
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibility)
})
onUnmounted(() => {
  disposed = true
  overviewVersion++
  sessionVersion++
  ticketVersion++
  attachmentVersion++
  window.clearInterval(timer)
  window.removeEventListener('storage', onStorage)
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <main class="support-workspace" data-testid="support-workspace" :data-role="actor.role">
    <header class="workspace-header">
      <div>
        <div class="eyebrow">CUSTOMER SUPPORT <span>{{isBackendMode()?'Java模拟服务':'本机演示'}}</span></div>
        <h1>{{ merchant ? '本店客服与留言' : '平台客服与留言' }}</h1>
        <p>{{ merchant ? '集中查看本店顾客的咨询和留言，让每一次沟通都有记录' : '查看各店铺的客服会话与留言，以平台客服身份参与沟通' }}</p>
      </div>
      <el-button :icon="Refresh" :loading="overviewLoading" data-testid="support-refresh" @click="refreshAll(true)">刷新记录</el-button>
    </header>

    <el-alert v-if="!allowed" type="error" :closable="false" title="请先以商家或平台身份登录" description="客服工作台需要有效的演示身份。" />
    <template v-else>
      <el-alert class="demo-notice" type="info" :closable="false" show-icon :title="isBackendMode()?'Java模拟服务：当前会话与权限由服务端校验':'本机演示：记录保存在当前浏览器'" description="客服与留言独立于订单售后和信用账本。支持常见图片、PDF、TXT、MP4，每次最多5个附件，单个512 KiB、合计1 MiB；文件未扫描。不会调用真实客服、AI或支付。"/>
      <div v-if="overviewError" class="inline-error" role="alert" data-testid="support-overview-error"><span>{{ overviewError }}</span><el-button link type="primary" @click="refreshAll(true)">重试</el-button></div>
      <section class="stats-grid" aria-label="客服概览">
        <article><div class="stat-label"><span>客服会话</span><el-icon><ChatDotRound /></el-icon></div><strong data-testid="support-session-count">{{ stats.sessions }}</strong><small>{{ merchant ? '仅展示本店会话' : '全平台店铺会话' }}</small></article>
        <article class="unread-stat"><div class="stat-label"><span>未读消息</span><span class="stat-dot"></span></div><strong data-testid="support-unread-count">{{ stats.unread }}</strong><small>打开会话后标记已读</small></article>
        <article><div class="stat-label"><span>待跟进留言</span><el-icon><Tickets /></el-icon></div><strong data-testid="support-active-ticket-count">{{ stats.active }}</strong><small>待处理与处理中</small></article>
        <article><div class="stat-label"><span>已解决留言</span><span class="resolved-dot"></span></div><strong>{{ stats.resolved }}</strong><small>待确认并关闭</small></article>
      </section>

      <section class="workspace-panel">
        <div class="panel-meta"><span class="identity-tag">{{ actorLabel }} · {{ actorName }}</span><span>每 5 秒同步本机记录<span v-if="lastSynced"> · 最近 {{ shortTime(lastSynced) }}</span></span></div>
        <el-tabs v-model="tab" class="support-tabs" data-testid="support-tabs">
          <el-tab-pane name="conversations">
            <template #label><span data-testid="support-conversations-tab">客服会话 <span class="tab-count">{{ sessions.length }}</span></span></template>
            <div class="conversation-layout">
              <aside class="session-sidebar" aria-label="会话列表">
                <div class="sidebar-search"><el-input v-model="sessionSearch" :prefix-icon="Search" clearable placeholder="搜索顾客、店铺或消息" aria-label="搜索客服会话" data-testid="support-session-search" /></div>
                <div class="session-list">
                  <el-empty v-if="!visibleSessions.length" :image-size="76" :description="sessionSearch ? '没有匹配的会话' : '还没有顾客咨询'" />
                  <button v-for="row in visibleSessions" :key="row.id" type="button" class="session-item" :class="{ selected: sameId(row.id, selectedSessionId) }" :aria-pressed="sameId(row.id, selectedSessionId)" :data-session-id="row.id" data-testid="support-session-row" @click="selectSession(row)">
                    <span class="avatar-circle">{{ initials(customerName(row)) }}</span>
                    <span class="session-copy"><span class="session-topline"><strong>{{ customerName(row) }}</strong><time>{{ shortTime(row.updatedAt) }}</time></span><span v-if="!merchant" class="session-shop">{{ shopName(row) }}</span><span class="last-message">{{ messageSummary(row.lastMessage) }}</span></span>
                    <span v-if="unread(row)" class="unread-badge" :aria-label="`${unread(row)} 条未读消息`" data-testid="support-session-unread">{{ unread(row) > 99 ? '99+' : unread(row) }}</span>
                  </button>
                </div>
                <div class="sidebar-foot">{{ merchant ? '店铺之间的会话独立保存' : '平台回复将明确显示“平台客服”' }}</div>
              </aside>

              <section class="thread-panel" aria-label="当前会话" data-testid="support-thread">
                <template v-if="selectedSessionId">
                  <header class="thread-header"><div><h2>{{ customerName(selectedSession || sessions.find(row => sameId(row.id, selectedSessionId))) }}</h2><p>{{ shopName(selectedSession || sessions.find(row => sameId(row.id, selectedSessionId))) }} · {{ selectedSessionId }}</p></div><el-tag size="small" effect="plain">{{ actorLabel }}回复</el-tag></header>
                  <div v-if="sessionError" class="inline-error" role="alert"><span>{{ sessionError }}</span><el-button link type="primary" @click="loadSession({ visible: true, markRead: true })">重新读取</el-button></div>
                  <div ref="messageList" v-loading="sessionLoading" class="message-list" role="log" aria-live="polite" aria-relevant="additions" aria-label="会话消息" data-testid="support-message-list">
                    <div v-if="!sessionLoading && !sessionError && !messages.length" class="thread-empty">这段会话还没有消息，可先发送一条回复</div>
                    <el-button v-if="sessionHasMore" link type="primary" :disabled="sessionLoading" @click="loadOlder">读取更早消息</el-button><article v-for="message in messages" :key="message.id" class="message-row" :class="{ own: isOwn(message), platform: message.senderRole === 'ROLE_ADMIN' }" :data-message-id="message.id" data-testid="support-message">
                      <div class="message-content"><div class="message-meta"><span>{{ message.senderName || senderLabel(message) }}</span><span class="role-label">{{ senderLabel(message) }}</span><time>{{ shortTime(message.createdAt) }}</time></div><div class="message-bubble"><p v-if="message.text">{{ message.text }}</p><ServiceAttachment v-for="(file,index) in media(message)" :key="file.id||index" :attachment="file"/></div></div>
                    </article>
                  </div>
                  <div class="composer">
                    <div class="composer-identity">以 <strong>{{ actorLabel }}</strong> 身份回复 · {{isBackendMode()?'保存到Java模拟服务':'保存到本机'}}</div>
                    <el-input v-model="messageText" type="textarea" :rows="3" :maxlength="1000" show-word-limit resize="none" :disabled="!selectedSession || messageSending || sessionLoading" placeholder="输入给顾客的回复（Ctrl / ⌘ + Enter 发送）" aria-label="客服回复内容" data-testid="support-message-input" @keydown.ctrl.enter.prevent="sendMessage" @keydown.meta.enter.prevent="sendMessage" />
                    <ServiceUpload v-model="messageAttachments" :disabled="messageSending||!selectedSession" :context-key="selectedSessionId+identity(actor)" @busy="attachmentLoading=$event"/><div class="composer-actions"><span>文件在当前模式中保存，未扫描</span><el-button type="primary" :loading="messageSending" :disabled="!canSend || sessionLoading" data-testid="support-send-message" @click="sendMessage">发送回复</el-button></div>
                  </div>
                </template>
                <div v-else class="no-selection"><div class="empty-icon"><el-icon><ChatDotRound /></el-icon></div><h2>选择一段会话开始处理</h2><p>顾客发起咨询后，会出现在左侧列表</p><span>{{ merchant ? '您只能查看和回复本店顾客的会话' : '平台可查看各店铺会话，所有回复保留平台身份' }}</span></div>
              </section>
            </div>
          </el-tab-pane>

          <el-tab-pane name="tickets">
            <template #label><span data-testid="support-tickets-tab">客户留言 <span class="tab-count">{{ tickets.length }}</span></span></template>
            <div class="tickets-workspace">
              <div class="ticket-toolbar"><el-input v-model="ticketSearch" :prefix-icon="Search" clearable placeholder="搜索标题、内容或顾客" aria-label="搜索客户留言" data-testid="support-ticket-search" /><el-select v-model="ticketStatus" clearable placeholder="全部状态" aria-label="留言状态筛选" data-testid="support-ticket-status-filter"><el-option v-for="(label, value) in STATUS" :key="value" :label="label" :value="value" /></el-select><el-select v-model="ticketPriority" clearable placeholder="全部优先级" aria-label="留言优先级筛选" data-testid="support-ticket-priority-filter"><el-option label="低" value="LOW"/><el-option label="中" value="MEDIUM"/><el-option label="高" value="HIGH"/></el-select><el-select v-if="!merchant" v-model="ticketShop" clearable placeholder="全部店铺" aria-label="留言店铺筛选"><el-option v-for="shop in shops" :key="shop.id" :label="shop.name" :value="shop.id" /></el-select><el-checkbox v-if="!merchant" v-model="includeArchived">含归档记录</el-checkbox><el-button @click="resetFilters">重置</el-button></div>
              <div class="ticket-list-meta"><span>共 {{ ticketTotal }} 条留言</span><span>处理流程：待处理 → 处理中 → 已解决 → 已关闭</span></div>
              <el-table :data="visibleTickets" row-key="id" class="ticket-table" data-testid="support-ticket-table" @row-click="openTicket">
                <el-table-column label="留言" min-width="235"><template #default="{ row }"><div class="ticket-title-cell"><strong>{{ row.title }}</strong><small>{{ row.id }}</small></div></template></el-table-column>
                <el-table-column label="顾客" min-width="115"><template #default="{ row }">{{ customerName(row) }}</template></el-table-column>
                <el-table-column v-if="!merchant" label="所属店铺" min-width="135"><template #default="{ row }">{{ shopName(row) }}</template></el-table-column>
                <el-table-column label="优先级" width="85"><template #default="{ row }"><span class="priority-label" :class="{ important: ['IMPORTANT','HIGH'].includes(row.priority) }"><i></i>{{priorityLabel(row.priority)}}</span></template></el-table-column>
                <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="statusType(row.status)" effect="light">{{row.archived?'已归档 · ':''}}{{ STATUS[row.status] || row.status }}</el-tag></template></el-table-column>
                <el-table-column label="附件" width="75"><template #default="{row}">{{media(row).length}}</template></el-table-column><el-table-column label="创建时间" width="145"><template #default="{row}">{{shortTime(row.createdAt)}}</template></el-table-column><el-table-column label="更新时间" width="145"><template #default="{ row }"><span class="table-date">{{ shortTime(row.updatedAt || row.createdAt) }}</span></template></el-table-column>
                <el-table-column label="操作" width="105" fixed="right"><template #default="{ row }"><el-button link type="primary" :data-ticket-id="row.id" data-testid="support-open-ticket" @click.stop="openTicket(row)">查看详情</el-button></template></el-table-column>
                <template #empty><el-empty :image-size="80" :description="tickets.length ? '没有匹配的留言，试试调整筛选条件' : '还没有客户留言'" /></template>
              </el-table><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10,20,50]" :total="ticketTotal" layout="total, sizes, prev, pager, next" style="margin-top:18px"/>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>

    <el-dialog v-model="ticketOpen" title="客户留言详情" width="min(900px, 94vw)" class="support-ticket-dialog" :close-on-click-modal="false" data-testid="support-ticket-dialog">
      <div v-loading="ticketLoading" class="ticket-detail-shell">
        <div v-if="ticketError" class="inline-error" role="alert"><span>{{ ticketError }}</span><el-button link type="primary" @click="loadTicket({ visible: true })">重试</el-button></div>
        <template v-if="selectedTicket">
          <div class="ticket-detail-header"><div><div class="detail-kicker">{{ selectedTicket.id }} · {{ shopName(selectedTicket) }}</div><h2>{{ selectedTicket.title }}</h2><p>{{ customerName(selectedTicket) }} · 提交于 {{ dateTime(selectedTicket.createdAt) }}</p></div><el-tag :type="statusType(selectedTicket.status)" data-testid="support-ticket-current-status">{{selectedTicket.archived?'已归档 · ':''}}{{ STATUS[selectedTicket.status] || selectedTicket.status }}</el-tag></div>
          <div class="ticket-properties"><span class="priority-label" :class="{ important: selectedTicket.priority === 'IMPORTANT' }"><i></i>{{priorityLabel(selectedTicket.priority)}}优先级</span><span v-if="selectedTicket.orderId">关联订单：{{ selectedTicket.orderId }}</span><span>版本 {{ selectedTicket.version }}</span></div>
          <section class="original-ticket"><h3>顾客留言</h3><p>{{ selectedTicket.content }}</p><ServiceAttachment v-for="(file,index) in media(selectedTicket)" :key="file.id||index" :attachment="file"/></section>
          <div class="ticket-status-bar"><div><strong>处理进度</strong><p>{{ selectedTicket.status === 'CLOSED' ? '已关闭，可按允许的操作重新开启' : selectedTicket.status === 'RESOLVED' ? '关闭后暂停回复，可按权限重新开启留言' : '按实际处理情况更新状态，历史记录会保留' }}</p></div><el-button v-if="!selectedTicket.archived&&NEXT_STATUS[selectedTicket.status]" :type="selectedTicket.status === 'RESOLVED' ? 'default' : 'primary'" :loading="ticketSaving" :disabled="ticketSaving || ticketLoading" data-testid="support-advance-ticket" @click="advanceTicket">{{ STATUS_ACTION[selectedTicket.status] }}</el-button><el-button v-else-if="!selectedTicket.archived&&selectedTicket.availableStatuses?.includes('OPEN')" :disabled="ticketSaving" @click="advanceTicket('OPEN')">重新打开</el-button><el-tag v-else type="info" effect="plain">{{selectedTicket.archived?'已归档，恢复后才可处理':'等待更新'}}</el-tag></div>
          <section class="ticket-replies"><h3>沟通记录 <span>{{ selectedTicket.replies?.length || 0 }}</span></h3><div v-if="!selectedTicket.replies?.length" class="small-empty">还没有回复，处理后可在下方补充说明</div><article v-for="reply in selectedTicket.replies || []" :key="reply.id" class="ticket-reply" :class="{ platform: reply.senderRole === 'ROLE_ADMIN' }" data-testid="support-ticket-reply"><div class="reply-meta"><strong>{{ reply.senderName || senderLabel(reply) }}</strong><el-tag size="small" :type="reply.senderRole === 'ROLE_ADMIN' ? 'warning' : 'info'" effect="plain">{{ senderLabel(reply) }}</el-tag><time>{{ dateTime(reply.createdAt) }}</time></div><p>{{ reply.text }}</p><ServiceAttachment v-for="(file,index) in media(reply)" :key="file.id||index" :attachment="file"/></article></section>
          <section class="ticket-reply-composer"><h3>追加回复 <small>{{ actorLabel }}身份</small></h3><el-alert v-if="!selectedTicket.canReply" type="info" :closable="false" title="当前留言已关闭或归档，恢复并重新开启后才可回复" /><template v-else><el-input v-model="ticketReply" type="textarea" :rows="3" :maxlength="1000" show-word-limit resize="none" :disabled="ticketSaving || ticketLoading" placeholder="填写处理说明，新回复会追加保存" aria-label="留言回复内容" data-testid="support-ticket-reply-input" /><ServiceUpload v-model="replyAttachments" :disabled="ticketSaving" :context-key="selectedTicketId+identity(actor)" @busy="replyUploading=$event"/><div class="reply-actions"><span>回复和状态变更均不触发退款或账务变动</span><el-button type="primary" :loading="ticketSaving" :disabled="!canReply" data-testid="support-reply-ticket" @click="replyTicket">保存回复</el-button></div></template></section>
          <div v-if="!merchant" style="margin:14px 0"><el-button v-if="selectedTicket.canArchive" :disabled="ticketSaving" @click="archiveTicket(false)">归档（可恢复）</el-button><el-button v-if="selectedTicket.canRestore" type="primary" :disabled="ticketSaving" @click="archiveTicket(true)">恢复留言</el-button></div><el-collapse class="ticket-history"><el-collapse-item title="查看完整处理记录" name="history"><el-timeline><el-timeline-item v-for="event in selectedTicket.history || []" :key="event.id" :timestamp="dateTime(event.createdAt)" placement="top"><strong>{{ event.actorName || event.actorLabel || '系统记录' }} <span class="history-role">{{ event.actorLabel || ({ ROLE_ADMIN: '平台客服', ROLE_UNIT: '本店商家', ROLE_USER: '顾客' }[event.actorRole] || '') }}</span></strong><p>{{ event.text || ({ CREATE: '创建留言', REPLY: '追加回复', STATUS: '更新状态' }[event.type] || event.type) }}<span v-if="event.fromStatus && event.toStatus"> · {{ STATUS[event.fromStatus] || event.fromStatus }} → {{ STATUS[event.toStatus] || event.toStatus }}</span></p></el-timeline-item></el-timeline></el-collapse-item></el-collapse>
        </template>
        <div v-else-if="!ticketError" class="small-empty">正在读取留言详情…</div>
      </div>
      <template #footer><el-button data-testid="support-close-ticket" @click="ticketOpen = false">关闭详情</el-button></template>
    </el-dialog>
    <el-dialog v-model="previewOpen" :title="imagePreview?.name || '本机图片预览'" width="min(760px, 92vw)" append-to-body><img v-if="previewOpen && safeImage(imagePreview)" class="preview-image" :src="safeImage(imagePreview)" :alt="imagePreview.name || '本机图片预览'" /><p class="preview-caption">图片仅保存在当前浏览器的演示数据中</p></el-dialog>
  </main>
</template>

<style scoped>
.support-workspace{min-width:700px;min-height:100%;padding:26px;background:#f5f7fb;color:#2d4058;--support-border:#e6ebf2;--support-muted:#8090a4;--support-primary:#3865c7}
.workspace-header{display:flex;align-items:center;justify-content:space-between;gap:24px;margin-bottom:22px}.eyebrow{display:flex;align-items:center;gap:10px;font-size:10px;font-weight:700;letter-spacing:1.8px;color:#8b99ac}.eyebrow span{padding:3px 7px;border-radius:4px;background:#e8edf6;color:#657791;font-size:10px;letter-spacing:.5px}.workspace-header h1{font-size:25px;letter-spacing:.5px;margin:9px 0 8px;font-weight:650;color:#263c59}.workspace-header p{margin:0;font-size:13px;color:#8190a4;line-height:1.8}.demo-notice{border:1px solid #dce6f5;border-radius:9px;background:#edf4fc}.demo-notice :deep(.el-alert__title){font-weight:600}.demo-notice :deep(.el-alert__description){line-height:1.7;color:#73849b}.inline-error{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 14px;background:#fff4f1;color:#b45e4c;border:1px solid #f2ded7;border-radius:6px;font-size:12px;margin:12px 0}.stats-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin:20px 0}.stats-grid article{padding:19px 21px;background:#fff;border:1px solid var(--support-border);border-radius:10px;box-shadow:0 3px 14px #22375903}.stat-label{display:flex;align-items:center;justify-content:space-between;color:#77889f;font-size:12px}.stat-label .el-icon{font-size:18px;color:#94a4bb}.stats-grid strong{display:block;font-size:28px;line-height:1;margin:14px 0 10px;font-weight:650;color:#2d4565;font-variant-numeric:tabular-nums}.stats-grid small{font-size:11px;color:#a0aab8}.unread-stat strong{color:#3865c7}.stat-dot,.resolved-dot{width:8px;height:8px;border-radius:50%;background:#ee9a69;box-shadow:0 0 0 4px #fff4eb}.resolved-dot{background:#71af98;box-shadow:0 0 0 4px #edf7f2}.workspace-panel{background:#fff;border:1px solid var(--support-border);border-radius:11px;overflow:hidden;box-shadow:0 6px 22px #21354d04}.panel-meta{display:flex;justify-content:space-between;gap:16px;align-items:center;padding:15px 20px 0;font-size:11px;color:#a0acba}.identity-tag{display:inline-flex;padding:5px 8px;background:#f1f4f9;border:1px solid #e8edf5;border-radius:5px;color:#71829a}.support-tabs :deep(.el-tabs__header){margin:0;padding:0 20px}.support-tabs :deep(.el-tabs__item){height:57px;font-size:14px;font-weight:600}.support-tabs :deep(.el-tabs__nav-wrap::after){height:1px;background:var(--support-border)}.support-tabs :deep(.el-tabs__content){padding:0}.tab-count{display:inline-block;padding:1px 6px;margin-left:5px;background:#f1f4f8;border-radius:5px;font-size:11px;font-weight:500;color:#8a98aa}.conversation-layout{display:grid;grid-template-columns:290px minmax(0,1fr);height:650px;min-height:530px}.session-sidebar{border-right:1px solid var(--support-border);display:flex;flex-direction:column;min-width:0;background:#fbfcfe}.sidebar-search{padding:16px 14px}.sidebar-search :deep(.el-input__wrapper){background:#fff;box-shadow:0 0 0 1px #e5ebf2 inset}.session-list{flex:1;overflow-y:auto}.session-item{position:relative;display:flex;align-items:flex-start;gap:11px;width:100%;border:0;border-left:3px solid transparent;border-bottom:1px solid #eef2f7;border-radius:0;padding:17px 14px 17px 11px;background:transparent;color:inherit;text-align:left;cursor:pointer;font:inherit;transition:background .15s}.session-item:hover{background:#f0f4fa}.session-item.selected{background:#edf3fd;border-left-color:#5d80cf}.session-item:focus-visible{outline:2px solid #7e9ad6;outline-offset:-2px}.avatar-circle{display:grid;place-items:center;flex-shrink:0;width:35px;height:35px;border-radius:11px;background:#e6ecf5;color:#8091aa;font-size:14px;font-weight:600}.selected .avatar-circle{background:#dce7fc;color:#5d7fc4}.session-copy{min-width:0;flex:1}.session-topline{display:flex;justify-content:space-between;align-items:center;gap:7px}.session-topline strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px;font-weight:600;color:#405572}.session-topline time{font-size:9px;color:#a0acbb;white-space:nowrap}.session-shop{display:block;font-size:10px;color:#879bb4;margin-top:5px;white-space:nowrap;text-overflow:ellipsis;overflow:hidden}.last-message{display:block;color:#97a3b4;font-size:11px;margin-top:8px;max-width:90%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.unread-badge{position:absolute;bottom:16px;right:13px;min-width:16px;padding:2px 4px;box-sizing:border-box;border-radius:10px;background:#e68f6c;color:white;font-size:9px;line-height:12px;text-align:center}.sidebar-foot{padding:13px 15px;border-top:1px solid var(--support-border);color:#a1adbd;font-size:10px;text-align:center}.thread-panel{display:flex;flex-direction:column;min-width:0;overflow:hidden}.thread-header{display:flex;align-items:center;justify-content:space-between;padding:17px 23px;gap:16px;border-bottom:1px solid var(--support-border)}.thread-header h2{font-size:15px;font-weight:650;margin:0 0 7px}.thread-header p{font-size:10px;color:#94a1b3;margin:0;overflow-wrap:anywhere}.thread-header .el-tag{flex-shrink:0}.thread-panel>.inline-error{margin:8px 15px}.message-list{flex:1;min-height:100px;overflow:auto;padding:21px 24px;background:#f8fafd;scrollbar-gutter:stable}.thread-empty{padding:25px 10px;text-align:center;font-size:12px;color:#9faabe}.message-row{display:flex;justify-content:flex-start;margin-bottom:20px}.message-content{max-width:84%;min-width:0}.message-meta{display:flex;flex-wrap:wrap;align-items:center;gap:6px 8px;color:#8d9baf;font-size:10px;margin-bottom:7px}.message-meta time{color:#abb5c4;font-size:9px}.role-label{padding:2px 5px;font-size:9px;border-radius:3px;background:#edf1f6;color:#91a0b4}.message-bubble{background:#fff;border:1px solid #e6edf5;border-radius:0 10px 10px 10px;padding:11px 14px;color:#54677e;font-size:13px;line-height:1.8;overflow-wrap:anywhere}.message-bubble p{margin:0;white-space:pre-wrap}.message-row.own{justify-content:flex-end}.own .message-meta{justify-content:flex-end}.own .message-bubble{background:#edf3fe;border-color:#dce7fb;border-radius:10px 0 10px 10px;color:#4b6590}.platform .role-label{background:#fff1df;color:#ad854c}.image-button{display:block;border:0;padding:0;border-radius:6px;overflow:hidden;margin:4px 0;background:transparent;cursor:zoom-in;max-width:100%}.image-button img{display:block;max-width:220px;max-height:180px;object-fit:contain;min-width:50px;max-inline-size:100%;border-radius:5px}.image-button:focus-visible{outline:2px solid #6187d9}.unavailable-image{font-size:11px;color:#9aa6b7}.composer{padding:14px 20px 15px;background:#fff;border-top:1px solid var(--support-border)}.composer-identity{font-size:10px;color:#a3aebe;margin-bottom:9px}.composer-identity strong{font-weight:500;color:#7791b2}.composer :deep(.el-textarea__inner){font-size:12px;line-height:1.8;background:#fbfcfe;box-shadow:0 0 0 1px #e6edf5 inset}.composer :deep(.el-input__count){background:#fbfcfe;font-size:10px;color:#a5b0c0}.composer-actions{display:flex;align-items:center;justify-content:space-between;margin-top:11px;gap:12px}.composer-actions>div{display:flex;align-items:center;gap:9px}.attachment-hint{color:#aab3c1;font-size:10px}.hidden-input{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap}.attachment-draft{display:flex;align-items:center;gap:10px;margin-top:10px;padding:8px 10px;background:#f6f9fe;border:1px solid #e3ebf6;border-radius:6px}.attachment-draft img{width:42px;height:42px;object-fit:cover;border-radius:4px}.attachment-draft>span{flex:1;font-size:11px;color:#6d7e95;overflow-wrap:anywhere}.attachment-draft small{display:block;font-size:10px;color:#9eabba;margin-top:5px}.no-selection{display:flex;flex-direction:column;align-items:center;justify-content:center;flex:1;padding:40px;text-align:center;background:linear-gradient(150deg,#fff,#f8fafd)}.empty-icon{display:grid;place-items:center;width:64px;height:64px;background:#edf2fa;border:1px solid #e6ecf6;border-radius:19px;color:#a4b7d4;font-size:29px;margin-bottom:22px}.no-selection h2{font-size:16px;font-weight:500;margin:0 0 12px;color:#647b98}.no-selection p{font-size:12px;color:#9ba9bb;margin:0 0 25px}.no-selection>span{font-size:10px;color:#adb8c6}.tickets-workspace{padding:20px}.ticket-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.ticket-toolbar>.el-input{width:240px;flex:1;min-width:180px}.ticket-toolbar>.el-select{width:135px}.ticket-list-meta{display:flex;justify-content:space-between;gap:14px;margin:19px 0 12px;color:#9da9b9;font-size:10px}.ticket-title-cell{padding:8px 0}.ticket-title-cell strong{display:block;color:#516781;font-size:12px;line-height:1.7;font-weight:500;overflow-wrap:anywhere}.ticket-title-cell small{display:block;color:#adb6c3;font-size:9px;margin-top:5px;overflow-wrap:anywhere}.ticket-table :deep(.el-table__row){cursor:pointer}.ticket-table :deep(th.el-table__cell){background:#f7f9fc;color:#95a1b3;font-size:11px;font-weight:500}.ticket-table :deep(td.el-table__cell){font-size:12px;color:#73869e}.table-date{font-size:10px;color:#9ba8b8}.priority-label{display:inline-flex;align-items:center;gap:5px;white-space:nowrap;font-size:11px;color:#93a2b5}.priority-label i{width:5px;height:5px;background:#b2bfce;border-radius:50%}.priority-label.important{color:#bf9567}.priority-label.important i{background:#d6ab7c}.ticket-detail-shell{min-height:100px}.ticket-detail-header{display:flex;justify-content:space-between;align-items:flex-start;gap:20px}.ticket-detail-header h2{font-size:20px;color:#354d6b;margin:9px 0 10px;line-height:1.5;overflow-wrap:anywhere}.detail-kicker{font-size:11px;color:#91a0b4;overflow-wrap:anywhere}.ticket-detail-header p{font-size:11px;color:#99a6b7;margin:0}.ticket-detail-header>.el-tag{flex-shrink:0;margin-top:5px}.ticket-properties{display:flex;flex-wrap:wrap;gap:20px;padding:15px 0;font-size:11px;color:#9aa8ba}.original-ticket{padding:17px 19px;background:#f8fafc;border:1px solid #e8edf5;border-radius:8px}.original-ticket h3,.ticket-replies h3,.ticket-reply-composer h3{font-size:13px;font-weight:600;color:#667d97;margin:0 0 13px}.original-ticket p,.ticket-reply p{font-size:13px;line-height:1.9;color:#607690;margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.original-ticket .image-button{margin-top:14px}.ticket-status-bar{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:17px 0;border-bottom:1px solid #edf1f6}.ticket-status-bar strong{font-size:12px;font-weight:600;color:#75889f}.ticket-status-bar p{font-size:11px;color:#a1adbc;margin:6px 0 0;line-height:1.7}.ticket-status-bar>.el-button,.ticket-status-bar>.el-tag{flex-shrink:0}.ticket-replies{padding:22px 0 0}.ticket-replies h3 span{margin-left:5px;padding:2px 6px;border-radius:4px;background:#eef3f9;color:#95a6bd;font-size:10px}.small-empty{padding:20px;text-align:center;font-size:12px;color:#acb6c6;background:#fbfcfe;border-radius:6px}.ticket-reply{border:1px solid #e7edf5;border-radius:8px;padding:15px 17px;margin-bottom:11px;background:#fff}.ticket-reply.platform{border-color:#eee5d5;background:#fffdfa}.reply-meta{display:flex;align-items:center;gap:9px;flex-wrap:wrap;margin-bottom:10px}.reply-meta strong{font-size:12px;color:#667f9c;font-weight:500}.reply-meta time{margin-left:auto;color:#a7b2c1;font-size:10px}.ticket-reply-composer{padding:20px 0}.ticket-reply-composer h3 small{margin-left:6px;font-weight:400;font-size:10px;color:#9fafc4}.ticket-reply-composer :deep(.el-textarea__inner){font-size:12px;line-height:1.8}.reply-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:13px}.reply-actions>span{font-size:10px;color:#a5b0c0}.ticket-history{margin-top:3px}.ticket-history :deep(.el-collapse-item__header){font-size:12px;color:#91a0b5;font-weight:400}.ticket-history :deep(.el-timeline){padding:12px 0 0 4px}.ticket-history :deep(.el-timeline-item__timestamp){font-size:10px}.ticket-history strong{font-size:12px;color:#8094ad;font-weight:500}.ticket-history p{font-size:11px;color:#a0adbd;line-height:1.8;margin:5px 0;white-space:pre-wrap;overflow-wrap:anywhere}.history-role{font-size:10px;font-weight:400;margin-left:6px;color:#a5b1c1}.preview-image{display:block;width:100%;max-height:65vh;object-fit:contain}.preview-caption{font-size:11px;color:#a3adbc;text-align:center;margin:18px 0 0}
@media(min-width:1500px){.conversation-layout{grid-template-columns:335px minmax(0,1fr);height:690px}.message-list{padding:26px 32px}.composer{padding:16px 28px}}
@media(max-width:1100px){.support-workspace{padding:20px}.conversation-layout{grid-template-columns:255px minmax(0,1fr)}.session-topline{display:block}.session-topline time{display:block;margin-top:5px;font-size:9px}.session-item{padding-right:12px}.stats-grid{gap:12px}.stats-grid article{padding:17px}.panel-meta{align-items:flex-start;line-height:1.6}.panel-meta>span:last-child{text-align:right}.ticket-toolbar>.el-select{width:123px}.ticket-list-meta{line-height:1.6}.thread-header{padding:15px 18px}.message-list{padding:18px}.composer{padding:13px 16px}}
@media(max-width:780px){.support-workspace{padding:16px;min-width:650px}.workspace-header p{font-size:12px}.workspace-header h1{font-size:23px}.conversation-layout{grid-template-columns:230px minmax(0,1fr)}.stats-grid{gap:9px}.stats-grid article{padding:15px}.stats-grid small{font-size:10px}.ticket-list-meta>span:last-child{max-width:250px}.attachment-hint{display:none}.panel-meta{font-size:10px}}
</style>
