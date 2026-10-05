<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Clock, Connection, Document, Edit, Plus, Refresh, Search, Wallet } from '@element-plus/icons-vue'
import request from '@/utils/request'

const props = defineProps({ section: { type: String, default: '' } })
const route = useRoute(), router = useRouter()
const section = computed(() => props.section || route.path.split('/').filter(Boolean).at(-1) || 'materials')
const isResources = computed(() => section.value === 'materials')
const isAI = computed(() => ['aiConfiguration', 'aiManagement', 'aiSessions'].includes(section.value))
const title = computed(() => isResources.value ? '帮助资料管理' : isAI.value ? 'AI 配置' : channels.find(row => row.route === section.value)?.title || '系统支付管理')
const channels = [
  { id: 'MOCK', route: 'paymentManagement', title: '模拟支付', mark: '模', description: '模拟环境中的测试渠道', color: 'mint' },
  { id: 'ALIPAY', route: 'payAlipay', title: '支付宝', mark: '支', description: '支付宝渠道展示配置', color: 'blue' },
  { id: 'WECHAT', route: 'payWechat', title: '微信支付', mark: '微', description: '微信支付渠道展示配置', color: 'green' },
  { id: 'BANKCARD', route: 'payBank', title: '银行卡', mark: '卡', description: '银行卡渠道展示配置', color: 'gold' }
]
const kindLabels = { KNOWLEDGE: '知识资料', MANUAL: '使用手册', UPDATE_LOG: '更新记录' }
const audienceLabels = { CUSTOMER: '顾客', MERCHANT: '商家', ALL: '全部用户' }
const historyLabels = { CREATED: '创建资料', UPDATED: '更新资料', ARCHIVED: '归档资料' }
const loading = ref(false), loadError = ref(''), saving = ref(false), actionError = ref(''), notice = ref(''), conflict = ref(false)
const resources = ref([]), page = ref(1), filters = reactive({ q: '', kind: '', audience: '', status: '' })
const editorOpen = ref(false), target = ref(null), resourceDraft = reactive({ kind: 'KNOWLEDGE', title: '', content: '', audience: 'ALL' })
const history = ref([]), historyLoading = ref(false), historyError = ref(''), historyExpanded = ref('')
const ai = ref(null), aiDraft = reactive({ enabled: false, model: '', welcomeMsg: '' })
const payments = ref([]), paymentDrafts = reactive({}), resourceBaseline = ref(''), aiBaseline = ref(''), paymentBaseline = ref('')
let disposed = false, generation = 0, listGeneration = 0, editorGeneration = 0, mutationKey = null
const PAGE_SIZE = 10
const snapshot = () => `${generation}|${section.value}|${route.fullPath}|${sessionStorage.getItem('account') || ''}`
const current = stamp => !disposed && stamp === snapshot()
const resourceJson = () => JSON.stringify({ kind: resourceDraft.kind, title: resourceDraft.title, content: resourceDraft.content, audience: resourceDraft.audience })
const aiJson = () => JSON.stringify({ enabled: aiDraft.enabled, model: aiDraft.model, welcomeMsg: aiDraft.welcomeMsg })
const paymentJson = () => JSON.stringify(paymentDrafts)
const editable = computed(() => editorOpen.value && (!target.value || !target.value.readOnly && target.value.status === 'ACTIVE'))
const resourceDirty = computed(() => editable.value && resourceJson() !== resourceBaseline.value)
const dirty = computed(() => isResources.value ? resourceDirty.value : isAI.value ? ai.value && aiJson() !== aiBaseline.value : payments.value.length && paymentJson() !== paymentBaseline.value)
const pageCount = computed(() => Math.max(1, Math.ceil(resources.value.length / PAGE_SIZE)))
const visibleResources = computed(() => resources.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))
const activeCount = computed(() => resources.value.filter(row => row.status === 'ACTIVE').length)
const selectedChannel = computed(() => channels.find(row => row.route === section.value && row.id !== 'MOCK'))
const displayedChannels = computed(() => selectedChannel.value ? [selectedChannel.value] : channels)
const date = value => { const d = new Date(value); return value && !Number.isNaN(d.getTime()) ? d.toLocaleString('zh-CN', { hour12: false }) : '未提供' }
const paymentRow = id => payments.value.find(row => row.id === id)

function unpack(response) {
  if (!response || response.error || (response.code !== undefined && String(response.code) !== '200')) {
    const e = new Error(response?.msg || response?.error?.message || response?.message || '请求未完成，请稍后重试')
    e.code = response?.error?.code || response?.errorCode || response?.code
    throw e
  }
  return response.data
}
function describe(e) { return e?.response?.data?.error?.message || e?.response?.data?.msg || e?.message || '网络异常，请重试' }
function markError(e) {
  const code = String(e?.response?.data?.error?.code || e?.response?.data?.code || e?.code || '')
  conflict.value = /VERSION|CONFLICT|STALE/.test(code) || /版本|冲突/.test(describe(e))
  actionError.value = conflict.value ? '资料或配置已被其他操作更新。你的修改仍保留，请载入最新版本后重新核对。' : describe(e)
}
function clearAction() { actionError.value = ''; notice.value = ''; conflict.value = false }
function confirmDiscard() { return !dirty.value || window.confirm('你有尚未保存的修改。确定放弃这些修改吗？') }
function guardRoute() {
  if (saving.value) { actionError.value = '正在提交，请等待结果后再离开'; return false }
  return confirmDiscard()
}
onBeforeRouteLeave(guardRoute)
onBeforeRouteUpdate(guardRoute)
function requestKey(path, body, params) {
  const signature = JSON.stringify([path, body, params])
  if (!mutationKey || mutationKey.signature !== signature) mutationKey = { signature, value: `settings-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`}` }
  return mutationKey.value
}
async function mutate(path, body, params = {}) {
  return unpack(await request.post(path, body, { params, headers: { 'Idempotency-Key': requestKey(path, body, params) } }))
}
async function loadResources() {
  const stamp = snapshot(), operation = ++listGeneration
  loading.value = true; loadError.value = ''
  const params = {}
  for (const field of ['q', 'kind', 'audience', 'status']) if (filters[field].trim()) params[field] = filters[field].trim()
  try {
    const rows = unpack(await request.get('/support/admin/resources', { params }))
    if (!current(stamp) || operation !== listGeneration) return
    if (!Array.isArray(rows)) throw new Error('资料列表格式不正确')
    resources.value = rows; page.value = Math.min(page.value, pageCount.value)
  } catch (e) { if (current(stamp) && operation === listGeneration) loadError.value = describe(e) }
  finally { if (current(stamp) && operation === listGeneration) loading.value = false }
}
async function loadAI() {
  const value = unpack(await request.get('/support/platform/ai'))
  if (!value || value.id !== 'AI') throw new Error('AI 配置响应格式不正确')
  return value
}
function applyAI(value) { ai.value = value; Object.assign(aiDraft, { enabled: value.enabled === true, model: value.model, welcomeMsg: value.welcomeMsg }); aiBaseline.value = aiJson() }
function applyPayments(rows) {
  payments.value = rows
  for (const key of Object.keys(paymentDrafts)) delete paymentDrafts[key]
  for (const row of rows) paymentDrafts[row.id] = { displayEnabled: row.displayEnabled === true, label: row.label }
  paymentBaseline.value = paymentJson()
}
async function loadScreen() {
  generation++; listGeneration++; editorGeneration++; mutationKey = null
  const stamp = snapshot()
  saving.value = false; loading.value = true; loadError.value = ''; clearAction(); editorOpen.value = false; target.value = null; resources.value = []; history.value = []; ai.value = null; payments.value = []; page.value = 1
  if (isResources.value) return loadResources()
  try {
    if (isAI.value) { const value = await loadAI(); if (current(stamp)) applyAI(value) }
    else {
      const rows = unpack(await request.get('/support/platform/payments'))
      if (!Array.isArray(rows) || channels.some(channel => !rows.some(row => row.id === channel.id))) throw new Error('支付配置响应格式不正确')
      if (current(stamp)) applyPayments(rows)
    }
  } catch (e) { if (current(stamp)) loadError.value = describe(e) }
  finally { if (current(stamp)) loading.value = false }
}
async function refreshScreen() { if (!saving.value && confirmDiscard()) await loadScreen() }
function applyResource(row) {
  target.value = row
  Object.assign(resourceDraft, { kind: row?.kind || 'KNOWLEDGE', title: row?.title || '', content: row?.content || '', audience: row?.audience || 'ALL' })
  resourceBaseline.value = resourceJson(); editorOpen.value = true; history.value = []; historyError.value = ''; historyExpanded.value = ''
}
function createResource() { if (saving.value || !confirmDiscard()) return; editorGeneration++; clearAction(); applyResource(null) }
function openResource(row) { if (saving.value || !confirmDiscard()) return; editorGeneration++; clearAction(); applyResource(row); void loadHistory() }
function closeEditor() { if (saving.value || !confirmDiscard()) return; editorGeneration++; editorOpen.value = false; target.value = null; clearAction() }
async function loadHistory() {
  const row = target.value, stamp = snapshot(), operation = editorGeneration
  history.value = []; historyError.value = ''; historyLoading.value = false
  if (!row || row.readOnly) return
  historyLoading.value = true
  try {
    const rows = unpack(await request.get('/support/resources/history', { params: { id: String(row.id) } }))
    if (current(stamp) && operation === editorGeneration) {
      if (!Array.isArray(rows)) throw new Error('资料历史格式不正确')
      history.value = rows.slice().sort((a, b) => Number(b.resourceVersion) - Number(a.resourceVersion))
    }
  } catch (e) { if (current(stamp) && operation === editorGeneration) historyError.value = describe(e) }
  finally { if (current(stamp) && operation === editorGeneration) historyLoading.value = false }
}
function validateResource() {
  if (!resourceDraft.title.trim() || resourceDraft.title.length > 160) return '标题需要 1–160 个字符'
  if (!resourceDraft.content.trim() || resourceDraft.content.length > 16000) return '内容需要 1–16000 个字符'
  return ''
}
async function saveResource() {
  if (saving.value || !editable.value) return
  clearAction(); actionError.value = validateResource(); if (actionError.value) return
  const stamp = snapshot(), operation = editorGeneration, existing = target.value, draft = JSON.parse(resourceJson())
  const body = existing ? { version: existing.version, ...draft } : draft
  saving.value = true
  try {
    const value = await mutate(existing ? '/support/resources/update' : '/support/resources/create', body, existing ? { id: String(existing.id) } : {})
    if (!current(stamp) || operation !== editorGeneration) return
    mutationKey = null; applyResource(value); notice.value = `资料已${existing ? '更新' : '创建'}，当前版本 ${value.version}`
    await Promise.all([loadResources(), loadHistory()])
  } catch (e) { if (current(stamp) && operation === editorGeneration) markError(e) }
  finally { if (current(stamp) && operation === editorGeneration) saving.value = false }
}
async function archiveResource() {
  if (saving.value || !target.value || !editable.value) return
  if (resourceDirty.value && !window.confirm('归档将放弃当前未保存的修改。确定继续吗？')) return
  if (!window.confirm(`确定归档「${target.value.title}」吗？归档后不再展示给用户，内容和历史仍会保留。`)) return
  const stamp = snapshot(), operation = editorGeneration, row = target.value
  clearAction(); saving.value = true
  try {
    const value = await mutate('/support/resources/archive', { version: row.version }, { id: String(row.id) })
    if (!current(stamp) || operation !== editorGeneration) return
    mutationKey = null; applyResource(value); notice.value = '资料已归档，历史记录已保留'
    await Promise.all([loadResources(), loadHistory()])
  } catch (e) { if (current(stamp) && operation === editorGeneration) markError(e) }
  finally { if (current(stamp) && operation === editorGeneration) saving.value = false }
}
async function reloadCurrent() {
  if (saving.value || !confirmDiscard()) return
  if (!isResources.value || !target.value) return loadScreen()
  const stamp = snapshot(), operation = ++editorGeneration, id = String(target.value.id)
  saving.value = true
  try {
    const value = unpack(await request.get('/support/resource', { params: { id } }))
    if (!current(stamp) || operation !== editorGeneration) return
    applyResource(value); clearAction(); notice.value = `已载入最新版本 ${value.version}`; await loadHistory()
  } catch (e) { if (current(stamp) && operation === editorGeneration) markError(e) }
  finally { if (current(stamp) && operation === editorGeneration) saving.value = false }
}
async function saveAI() {
  if (saving.value || !ai.value) return
  clearAction()
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$/.test(aiDraft.model) || /^sk-/i.test(aiDraft.model)) { actionError.value = '模型名称需为 1–80 个字母、数字、点、下划线、连字符或冒号，不能填写密钥、网址或路径'; return }
  if (!aiDraft.welcomeMsg.trim() || aiDraft.welcomeMsg.length > 500) { actionError.value = '欢迎语需要 1–500 个字符'; return }
  const stamp = snapshot(), body = { version: ai.value.version, enabled: aiDraft.enabled, model: aiDraft.model, welcomeMsg: aiDraft.welcomeMsg }
  saving.value = true
  try { const value = await mutate('/support/platform/ai', body); if (current(stamp)) { mutationKey = null; applyAI(value); notice.value = `AI 配置已保存为版本 ${value.version}，连接状态仍为未连接` } }
  catch (e) { if (current(stamp)) markError(e) }
  finally { if (current(stamp)) saving.value = false }
}
async function savePayment(id) {
  const row = paymentRow(id), draft = paymentDrafts[id]
  if (saving.value || !row || !draft) return
  clearAction()
  if (!draft.label.trim() || draft.label.length > 80) { actionError.value = '渠道名称需要 1–80 个字符'; return }
  const stamp = snapshot(), body = { version: row.version, displayEnabled: draft.displayEnabled, label: draft.label }
  saving.value = true
  try {
    const value = await mutate('/support/platform/payments', body, { id })
    if (!current(stamp)) return
    mutationKey = null; payments.value = payments.value.map(item => item.id === id ? value : item)
    paymentDrafts[id] = { displayEnabled: value.displayEnabled === true, label: value.label }
    const baseline = JSON.parse(paymentBaseline.value); baseline[id] = { ...paymentDrafts[id] }; paymentBaseline.value = JSON.stringify(baseline)
    notice.value = `${value.label}的展示配置已保存，版本 ${value.version}`
  } catch (e) { if (current(stamp)) markError(e) }
  finally { if (current(stamp)) saving.value = false }
}
function paymentDirty(id) { const baseline = paymentBaseline.value ? JSON.parse(paymentBaseline.value) : {}; return JSON.stringify(paymentDrafts[id]) !== JSON.stringify(baseline[id]) }
watch(() => [section.value, route.fullPath], loadScreen, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++; listGeneration++; editorGeneration++ })
</script>

<template>
  <main class="service-settings" :data-settings-section="section">
    <header class="settings-header"><div><span class="settings-eyebrow">平台管理 / {{ isResources ? '内容与帮助' : '服务配置' }}</span><h1>{{ title }}</h1><p>{{ isResources ? '统一维护帮助内容、手册和更新说明，保留每次变更历史' : isAI ? '维护模型名称、欢迎语与启用意向，查看当前连接状态' : '分别维护渠道名称与展示状态，查看模拟及连接状态' }}</p></div><div class="header-actions"><button class="soft-button" :disabled="saving || loading" data-testid="settings-refresh" @click="refreshScreen"><Refresh /> 刷新</button><button v-if="isResources" class="primary-button" :disabled="saving" data-testid="resource-create" @click="createResource"><Plus /> 新建资料</button></div></header>
    <div v-if="actionError" class="feedback-banner error-banner" role="alert" data-testid="settings-action-error"><span>{{ actionError }}</span><button v-if="conflict" :disabled="saving" data-testid="settings-reload-latest" @click="reloadCurrent">载入最新版本</button></div>
    <div v-if="notice" class="feedback-banner success-banner" role="status">{{ notice }}</div>
    <div v-if="loadError" class="settings-empty error-banner" role="alert"><Document /><h2>暂时无法读取{{ isResources ? '资料' : '配置' }}</h2><p>{{ loadError }}</p><button class="soft-button" :disabled="saving" @click="isResources ? loadResources() : refreshScreen()">重新读取</button></div>
    <template v-if="isResources">
      <section class="resource-statistics"><article><span class="stat-icon blue"><Document /></span><div><small>当前筛选资料</small><strong>{{ resources.length }}</strong></div></article><article><span class="stat-icon mint"><Edit /></span><div><small>使用中</small><strong>{{ activeCount }}</strong></div></article><article><span class="stat-icon gold"><Clock /></span><div><small>已归档</small><strong>{{ resources.filter(row => row.status === 'ARCHIVED').length }}</strong></div></article></section>
      <section class="settings-panel resource-panel"><form class="resource-filters" @submit.prevent="page = 1; loadResources()"><div class="filter-search"><Search /><input v-model="filters.q" maxlength="100" aria-label="搜索资料" placeholder="搜索标题或正文" :disabled="saving" /></div><select v-model="filters.kind" aria-label="资料类型" :disabled="saving" @change="page = 1; loadResources()"><option value="">全部类型</option><option v-for="(label, key) in kindLabels" :key="key" :value="key">{{ label }}</option></select><select v-model="filters.audience" aria-label="资料对象" :disabled="saving" @change="page = 1; loadResources()"><option value="">全部对象</option><option v-for="(label, key) in audienceLabels" :key="key" :value="key">{{ label }}</option></select><select v-model="filters.status" aria-label="资料状态" :disabled="saving" @change="page = 1; loadResources()"><option value="">全部状态</option><option value="ACTIVE">使用中</option><option value="ARCHIVED">已归档</option></select><button type="submit" class="soft-button" :disabled="loading || saving">搜索</button></form><div class="panel-description"><span>正文按纯文字展示</span><span>内置资料只读，可新建补充内容</span></div>
        <div v-if="loading" class="settings-empty compact" role="status"><span class="settings-spinner"></span><p>正在读取资料…</p></div>
        <div v-else-if="!resources.length && !loadError" class="settings-empty compact"><Document /><h2>没有找到相关资料</h2><p>调整筛选条件，或新建一份帮助资料</p></div>
        <div v-else-if="resources.length" class="resource-table-scroll"><table class="resource-table" data-testid="resource-table"><thead><tr><th>标题 / 编号</th><th>类型</th><th>适用对象</th><th>状态</th><th>版本</th><th>最近记录</th><th>操作</th></tr></thead><tbody><tr v-for="row in visibleResources" :key="row.id" :class="{ selected: target?.id === row.id }"><td><strong>{{ row.title }}</strong><small>#{{ row.id }} <span v-if="row.readOnly">· 内置只读</span></small></td><td>{{ kindLabels[row.kind] }}</td><td>{{ audienceLabels[row.audience] }}</td><td><span class="status-tag" :class="row.status === 'ACTIVE' ? 'mint' : 'muted'">{{ row.status === 'ACTIVE' ? '使用中' : '已归档' }}</span></td><td>v{{ row.version }}</td><td class="table-date">{{ date(row.updatedAt || row.createdAt) }}</td><td><button class="text-button" :disabled="saving" :data-resource-id="row.id" @click="openResource(row)">{{ row.readOnly || row.status === 'ARCHIVED' ? '查看' : '编辑' }}</button></td></tr></tbody></table></div>
        <nav v-if="resources.length" class="table-pagination" aria-label="资料分页"><span>共 {{ resources.length }} 份</span><button :disabled="page === 1" @click="page--">上一页</button><button v-for="number in pageCount" :key="number" :class="{ active: number === page }" :aria-current="number === page ? 'page' : undefined" @click="page = number">{{ number }}</button><button :disabled="page === pageCount" @click="page++">下一页</button></nav>
      </section>
      <section v-if="editorOpen" class="settings-panel editor-panel" data-testid="resource-editor"><header class="panel-header"><div><span class="settings-eyebrow">{{ target ? `资料 #${target.id} · 版本 ${target.version}` : '新资料' }}</span><h2>{{ target?.readOnly ? '查看内置资料' : target?.status === 'ARCHIVED' ? '查看归档资料' : target ? '编辑资料' : '新建资料' }}</h2></div><button class="soft-button" :disabled="saving" @click="closeEditor">关闭</button></header><p v-if="target?.readOnly" class="info-note">这份内置资料不可编辑或归档。如需增加内容，请新建资料。</p><p v-else-if="target?.status === 'ARCHIVED'" class="info-note">资料已归档，内容和版本历史保留用于核对。</p><p v-if="target?.source === 'SOURCE_IMPLEMENTATION'" class="info-note">此内容为源码实现说明，不表示已发布或部署。</p>
        <div class="editor-columns"><form class="resource-editor-form" @submit.prevent="saveResource"><fieldset :disabled="!editable || saving"><label>标题 <span>{{ resourceDraft.title.length }} / 160</span><input v-model="resourceDraft.title" maxlength="160" required data-testid="resource-title" /></label><div class="form-row"><label>资料类型<select v-model="resourceDraft.kind" data-testid="resource-kind"><option v-for="(label, key) in kindLabels" :key="key" :value="key">{{ label }}</option></select></label><label>适用对象<select v-model="resourceDraft.audience" data-testid="resource-audience"><option v-for="(label, key) in audienceLabels" :key="key" :value="key">{{ label }}</option></select></label></div><label>正文 <span>{{ resourceDraft.content.length }} / 16000</span><textarea v-model="resourceDraft.content" maxlength="16000" rows="13" required data-testid="resource-content"></textarea></label></fieldset><p class="field-note">所有文字按原文展示；自定义手册可阅读并下载 TXT。</p><div v-if="editable" class="editor-actions"><button type="submit" class="primary-button" :disabled="saving || !resourceDirty" data-testid="resource-save">{{ saving ? '正在提交…' : target ? '保存新版本' : '创建资料' }}</button><button v-if="target" type="button" class="archive-button" :disabled="saving" data-testid="resource-archive" @click="archiveResource">归档资料</button><span v-if="resourceDirty">有未保存修改</span></div></form>
        <aside class="resource-history"><h3><Clock /> 版本历史</h3><p v-if="!target" class="field-note">资料创建后将在此记录变更。</p><p v-else-if="target.readOnly" class="field-note">内置资料为固定内容，没有修改历史。</p><p v-else-if="historyLoading" class="field-note" role="status">正在读取历史…</p><div v-else-if="historyError" class="history-error" role="alert"><p>{{ historyError }}</p><button class="text-button" @click="loadHistory">重试</button></div><p v-else-if="!history.length" class="field-note">暂无历史记录。</p><ol v-else data-testid="resource-history"><li v-for="entry in history" :key="entry.id"><strong>v{{ entry.resourceVersion }} · {{ historyLabels[entry.action] || entry.action }}</strong><time>{{ date(entry.createdAt) }}</time><span>操作者 {{ entry.actorId }}</span><button class="text-button" :aria-expanded="historyExpanded === entry.id" @click="historyExpanded = historyExpanded === entry.id ? '' : entry.id">{{ historyExpanded === entry.id ? '收起快照' : '查看快照' }}</button><div v-if="historyExpanded === entry.id" class="history-snapshot"><h4>{{ entry.snapshot?.title }}</h4><p>{{ entry.snapshot?.content }}</p><small>SHA-256：{{ entry.snapshotHash }}</small></div></li></ol></aside></div>
      </section>
    </template>
    <div v-else-if="loading" class="settings-panel settings-empty" role="status"><span class="settings-spinner"></span><p>正在读取配置…</p></div>
    <template v-else-if="isAI && ai && !loadError">
      <section class="configuration-notice"><Connection /><div><strong>模型服务未连接</strong><p>这里只保存配置意向。启用意向开关不会连接模型或触发自动回复。</p></div><span class="status-tag muted" data-testid="ai-status">NOT_CONNECTED</span></section>
      <div class="configuration-columns"><section class="settings-panel configuration-form"><header class="panel-header"><div><span class="settings-eyebrow">AI CONFIGURATION</span><h2>基础配置</h2></div><span class="version-badge">版本 {{ ai.version }}</span></header><form @submit.prevent="saveAI"><fieldset :disabled="saving"><div class="toggle-row"><div><strong>启用意向</strong><p>仅保存展示与配置意向</p></div><label class="switch"><input v-model="aiDraft.enabled" type="checkbox" aria-label="AI 启用意向" data-testid="ai-enabled" /><span></span></label></div><label class="settings-field">模型名称<input v-model="aiDraft.model" maxlength="80" placeholder="unconfigured" required data-testid="ai-model" /><small>例如模型标识；请勿填写密钥、网址或路径</small></label><label class="settings-field">欢迎语<textarea v-model="aiDraft.welcomeMsg" rows="5" maxlength="500" required data-testid="ai-welcome"></textarea><small>{{ aiDraft.welcomeMsg.length }} / 500 · 以纯文字保存</small></label></fieldset><div class="form-bottom"><button class="primary-button" type="submit" :disabled="saving || !dirty" data-testid="ai-save">{{ saving ? '正在保存…' : '保存配置' }}</button><span>{{ ai.updatedAt ? `最近保存 ${date(ai.updatedAt)}` : '当前为默认配置' }}</span></div></form></section><aside class="settings-panel configuration-state"><div class="config-symbol"><Connection /></div><h2>连接状态</h2><dl><dt>模型服务</dt><dd><i></i>未连接</dd><dt>外部模型调用</dt><dd>未启用</dd><dt>密钥配置</dt><dd>未配置</dd><dt>配置范围</dt><dd>仅元数据</dd><dt>当前环境</dt><dd>模拟环境</dd></dl><p class="field-note">保存名称与欢迎语不会建立外部连接。此处没有连接测试或密钥输入。</p><button v-if="section === 'aiSessions' || section === 'aiManagement'" class="soft-button" @click="router.push('/platform/support')">打开客服工作台 <ArrowRight /></button></aside></div>
    </template>
    <template v-else-if="!isAI && payments.length && !loadError">
      <button v-if="selectedChannel" class="back-button" @click="router.push('/platform/paymentManagement')"><ArrowLeft /> 返回系统支付管理</button><section class="configuration-notice"><Wallet /><div><strong>模拟渠道就绪，真实支付渠道未连接</strong><p>可保存展示状态与名称；真实渠道不会扣款，也不会调用外部支付服务。</p></div><span class="status-tag gold">模拟环境</span></section>
      <section class="payment-grid" :class="{ single: selectedChannel }"><article v-for="channel in displayedChannels" :key="channel.id" class="settings-panel payment-panel" :data-payment-id="channel.id"><header class="payment-heading"><span class="payment-symbol" :class="channel.color">{{ channel.mark }}</span><span class="status-tag" :class="channel.id === 'MOCK' ? 'mint' : 'muted'">{{ channel.id === 'MOCK' ? '模拟就绪' : '未连接' }}</span></header><h2>{{ channel.title }}</h2><p class="payment-description">{{ channel.description }}</p><form v-if="paymentDrafts[channel.id]" @submit.prevent="savePayment(channel.id)"><fieldset :disabled="saving"><div class="toggle-row"><div><strong>显示此渠道</strong><p>仅控制展示意向</p></div><label class="switch"><input v-model="paymentDrafts[channel.id].displayEnabled" type="checkbox" :aria-label="`${channel.title}展示开关`" :data-testid="`payment-enabled-${channel.id}`" /><span></span></label></div><label class="settings-field">展示名称<input v-model="paymentDrafts[channel.id].label" maxlength="80" required :data-testid="`payment-label-${channel.id}`" /></label></fieldset><dl class="payment-state"><dt>渠道标识</dt><dd>{{ channel.id }}</dd><dt>服务状态</dt><dd :data-testid="`payment-status-${channel.id}`">{{ channel.id === 'MOCK' ? 'READY / MOCK' : 'NOT_CONNECTED' }}</dd><dt>外部连接 / 真实扣款</dt><dd>均未启用</dd><dt>配置版本</dt><dd>v{{ paymentRow(channel.id)?.version }}</dd></dl><div class="payment-bottom"><button class="primary-button" :disabled="saving || !paymentDirty(channel.id)" type="submit" :data-testid="`payment-save-${channel.id}`">{{ saving ? '正在保存…' : '保存配置' }}</button><button v-if="!selectedChannel && channel.id !== 'MOCK'" class="text-button" type="button" @click="router.push('/platform/' + channel.route)">独立管理 <ArrowRight /></button></div></form></article></section><p class="settings-footer">渠道配置仅包含展示开关与名称。模拟渠道的 READY 状态不代表真实支付可用。</p>
    </template>
  </main>
</template>

<style scoped>
.service-settings{--border:#e6ecf2;--ink:#3d5571;--muted:#91a0b3;--blue:#6284bc;padding:28px;max-width:1440px;margin:0 auto;color:var(--ink);font-family:inherit}.service-settings *{box-sizing:border-box}.service-settings button,.service-settings input,.service-settings textarea,.service-settings select{font:inherit}.service-settings button{cursor:pointer}.service-settings button:disabled{cursor:not-allowed;opacity:.45}.service-settings button:focus-visible,.service-settings input:focus-visible,.service-settings select:focus-visible,.service-settings textarea:focus-visible{outline:2px solid #81a1d4;outline-offset:3px}.service-settings svg{width:17px;height:17px;flex-shrink:0}.settings-header{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:25px}.settings-eyebrow{font-size:10px;color:#a0adbd;letter-spacing:.5px}.settings-header h1{font-size:25px;letter-spacing:-.5px;margin:10px 0 9px;font-weight:650}.settings-header p{font-size:12px;color:#91a0b1;margin:0;line-height:1.8}.header-actions{display:flex;align-items:center;gap:10px;flex-shrink:0}.primary-button,.soft-button,.archive-button{display:inline-flex;align-items:center;justify-content:center;gap:6px;border-radius:6px;padding:10px 15px;font-size:11px;white-space:nowrap}.primary-button{background:#6687bd;color:white;border:1px solid #6687bd}.soft-button{background:#fff;color:#859ab3;border:1px solid #e1e8f1}.archive-button{background:#fff9f3;color:#b58b67;border:1px solid #eedfce}.text-button{display:inline-flex;align-items:center;gap:4px;padding:2px 0;background:none;border:0;color:#7292bd;font-size:11px}.text-button svg{width:12px;height:12px}.feedback-banner{display:flex;justify-content:space-between;align-items:center;gap:20px;padding:13px 17px;border-radius:7px;margin-bottom:18px;font-size:12px;line-height:1.7}.feedback-banner button{flex-shrink:0;background:#fff;border:1px solid #e9d6c3;border-radius:5px;color:#a57c56;padding:7px 11px;font-size:11px}.error-banner{background:#fff7ef;border:1px solid #eedfcd;color:#b18c69}.success-banner{background:#f1f8f2;border:1px solid #dfebdf;color:#729779}.settings-panel{background:#fff;border:1px solid var(--border);border-radius:10px;box-shadow:0 3px 9px #203a5903}.resource-statistics{display:grid;grid-template-columns:repeat(3,1fr);gap:18px;margin-bottom:22px}.resource-statistics article{display:flex;align-items:center;gap:17px;border:1px solid var(--border);border-radius:9px;background:#fff;padding:20px 23px}.stat-icon{display:grid;place-items:center;width:46px;height:46px;border-radius:11px}.stat-icon svg{width:21px;height:21px}.resource-statistics small{display:block;font-size:11px;color:#97a4b6;margin-bottom:8px}.resource-statistics strong{font-size:24px;font-weight:600;color:#526a88;line-height:1}.blue{background:#edf3fd!important;color:#7396cc!important}.mint{background:#eef7f1!important;color:#7ca68c!important}.gold{background:#fbf5e9!important;color:#b39d72!important}.green{background:#edf6ed!important;color:#78a77b!important}.muted{background:#f0f3f7!important;color:#94a0b1!important}.resource-filters{display:flex;gap:10px;padding:20px 20px 13px;align-items:center}.filter-search{flex:1;display:flex;align-items:center;gap:9px;padding:9px 11px;border:1px solid #e2e8f1;border-radius:6px;min-width:145px}.filter-search svg{color:#a2b0c2;width:15px}.filter-search input{border:0;background:none;outline:none;color:#6a7f98;min-width:0;width:100%;font-size:11px}.resource-filters select{border:1px solid #e2e8f1;border-radius:6px;padding:9px 11px;background:white;color:#8295ae;font-size:11px;max-width:155px}.panel-description{display:flex;justify-content:space-between;font-size:10px;color:#a3afbf;padding:0 22px 17px}.resource-table-scroll{width:100%;overflow-x:auto}.resource-table{width:100%;border-collapse:collapse;text-align:left;min-width:760px}.resource-table th{padding:13px 20px;background:#f7f9fc;color:#98a6b8;font-size:10px;font-weight:500;white-space:nowrap;border-top:1px solid var(--border);border-bottom:1px solid var(--border)}.resource-table td{padding:15px 20px;font-size:11px;border-bottom:1px solid #edf1f6;color:#8192a8}.resource-table td:first-child{width:29%;max-width:300px}.resource-table td strong{display:block;font-size:12px;font-weight:500;color:#5d7390;line-height:1.65;overflow-wrap:anywhere}.resource-table td small{display:block;font-size:9px;color:#aab4c2;margin-top:5px}.resource-table tr.selected{background:#f8faff}.resource-table .table-date{font-size:10px;color:#a1adbd;max-width:130px;line-height:1.7}.status-tag{display:inline-block;white-space:nowrap;border-radius:4px;padding:5px 8px;font-size:10px;line-height:1}.table-pagination{display:flex;align-items:center;justify-content:flex-end;gap:6px;padding:17px 20px;flex-wrap:wrap}.table-pagination>span{font-size:10px;color:#a1adbd;margin-right:auto}.table-pagination button{background:#fff;border:1px solid #e5ebf2;border-radius:4px;min-width:26px;min-height:27px;padding:4px 7px;color:#8a9ab0;font-size:10px}.table-pagination button.active{background:#eef3fc;color:#6485be;border-color:#dbe6f8}.settings-empty{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:13px;text-align:center;min-height:230px;padding:30px;margin-bottom:22px}.settings-empty.compact{margin:0;min-height:240px}.settings-empty>svg{width:38px;height:38px;color:#bcc9d8}.settings-empty h2{font-size:15px;font-weight:500;margin:0}.settings-empty p{font-size:12px;color:#9aa9bc;line-height:1.8;margin:0;overflow-wrap:anywhere}.settings-spinner{width:25px;height:25px;border:2px solid #e3eaf4;border-top-color:#81a1d1;border-radius:50%;animation:settings-spin .8s linear infinite}@keyframes settings-spin{to{transform:rotate(360deg)}}.editor-panel{margin-top:24px;padding:24px}.panel-header{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:20px}.panel-header h2{font-size:17px;font-weight:600;margin:8px 0 0}.info-note{font-size:11px;color:#9a987e;background:#faf8f0;border:1px solid #eeebdc;border-radius:5px;padding:11px 14px;line-height:1.9;margin:0 0 18px}.editor-columns{display:grid;grid-template-columns:minmax(0,1fr) 290px;gap:30px}.service-settings fieldset{border:0;padding:0;margin:0;min-width:0}.resource-editor-form label,.settings-field{display:block;font-size:11px;color:#8091a8;margin-bottom:17px;line-height:1.5}.resource-editor-form label>span{float:right;color:#b0bac8;font-size:9px}.service-settings fieldset input:not([type=checkbox]),.service-settings fieldset select,.service-settings fieldset textarea{display:block;width:100%;border:1px solid #e1e8f1;border-radius:6px;background:white;padding:10px 12px;color:#637a95;font-size:12px;margin-top:8px;line-height:1.7;resize:vertical}.service-settings fieldset:disabled input:not([type=checkbox]),.service-settings fieldset:disabled select,.service-settings fieldset:disabled textarea{background:#f8fafc;color:#8c9bb0;opacity:1;-webkit-text-fill-color:#8c9bb0}.form-row{display:grid;grid-template-columns:1fr 1fr;gap:18px}.field-note{font-size:10px;color:#a1adbf;line-height:1.9;margin:0}.editor-actions{display:flex;align-items:center;gap:10px;margin-top:19px;flex-wrap:wrap}.editor-actions>span{margin-left:auto;color:#b6a37e;font-size:10px}.resource-history{border-left:1px solid #edf1f6;padding-left:25px;min-width:0}.resource-history h3{display:flex;align-items:center;gap:7px;font-size:12px;font-weight:500;color:#8095af;margin:0 0 22px}.resource-history ol{list-style:none;margin:0;padding:0 0 0 13px;border-left:1px solid #e5ebf3}.resource-history li{position:relative;margin:0 0 22px;padding-left:6px}.resource-history li:before{content:'';width:6px;height:6px;border-radius:50%;background:#a5bada;position:absolute;left:-17px;top:6px}.resource-history li strong{display:block;font-size:11px;font-weight:500;color:#7e93ae;margin-bottom:8px}.resource-history li time,.resource-history li>span{display:block;font-size:9px;color:#a9b5c4;margin-bottom:7px}.resource-history .text-button{font-size:10px;margin-top:2px}.history-error{font-size:11px;line-height:1.8;color:#ba9982}.history-snapshot{margin-top:12px;padding:12px;background:#f7f9fc;border:1px solid #e8edf4;border-radius:5px;max-height:300px;overflow:auto}.history-snapshot h4{font-size:11px;color:#7890ac;margin:0 0 9px;overflow-wrap:anywhere}.history-snapshot p{white-space:pre-wrap;overflow-wrap:anywhere;font-size:10px;line-height:1.9;color:#90a0b6;margin:0}.history-snapshot small{display:block;font-size:8px;line-height:1.7;color:#b1bccb;overflow-wrap:anywhere;margin-top:10px}.configuration-notice{display:flex;gap:14px;align-items:center;background:#f3f6fb;border:1px solid #e4eaf3;border-radius:8px;padding:18px 21px;margin-bottom:23px}.configuration-notice>svg{color:#8ea7cb;width:23px;height:23px}.configuration-notice>div{flex:1}.configuration-notice strong{font-size:12px;color:#7189a9;font-weight:500}.configuration-notice p{font-size:11px;color:#9baabd;line-height:1.9;margin:7px 0 0}.configuration-notice>.status-tag{flex-shrink:0}.configuration-columns{display:grid;grid-template-columns:minmax(0,1fr) 340px;gap:23px}.configuration-form{padding:25px}.version-badge{padding:5px 8px;border-radius:5px;background:#f2f5fa;color:#9ba9bc;font-size:10px}.toggle-row{display:flex;align-items:center;justify-content:space-between;gap:15px;padding:14px 0 20px;margin-bottom:17px;border-bottom:1px solid #edf1f7}.toggle-row strong{font-size:12px;font-weight:500;color:#8195af}.toggle-row p{font-size:10px;color:#a6b2c3;line-height:1.8;margin:5px 0 0}.switch{position:relative;width:34px;height:20px;display:block;flex-shrink:0;cursor:pointer;margin:0!important}.switch input{position:absolute;opacity:0;width:100%;height:100%;margin:0;cursor:pointer}.switch span{display:block;width:34px;height:20px;background:#dce3ed;border-radius:12px;transition:.15s;pointer-events:none}.switch span:after{content:'';position:absolute;left:3px;top:3px;width:14px;height:14px;border-radius:50%;background:white;transition:.15s;box-shadow:0 1px 3px #0001}.switch input:checked+span{background:#7c9aca}.switch input:checked+span:after{transform:translateX(14px)}.switch input:focus-visible+span{outline:2px solid #8cabd9;outline-offset:3px}.switch input:disabled+span{opacity:.5}.settings-field>small{display:block;font-size:10px;color:#a6b2c3;margin-top:9px}.form-bottom{display:flex;align-items:center;gap:15px;border-top:1px solid #edf1f7;padding-top:22px;margin-top:23px}.form-bottom>span{font-size:10px;color:#acb6c5;margin-left:auto}.configuration-state{padding:27px}.config-symbol{display:grid;place-items:center;width:47px;height:47px;background:#edf3fd;border-radius:13px;color:#8aa7d1}.config-symbol svg{width:23px;height:23px}.configuration-state h2{font-size:16px;font-weight:500;margin:20px 0 25px;color:#7187a4}.configuration-state dl{display:grid;grid-template-columns:1fr 1fr;font-size:11px;gap:20px 10px;margin:0 0 28px}.configuration-state dt{color:#a5b0c2}.configuration-state dd{margin:0;text-align:right;color:#8297b3}.configuration-state dd i{display:inline-block;width:5px;height:5px;background:#b6c1d0;border-radius:50%;margin-right:6px}.configuration-state .soft-button{margin-top:22px}.payment-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:21px}.payment-grid.single{grid-template-columns:minmax(0,640px)}.payment-panel{padding:24px}.payment-heading{display:flex;justify-content:space-between;align-items:center;gap:20px}.payment-symbol{display:grid;place-items:center;width:43px;height:43px;border-radius:11px;font-size:21px}.payment-panel h2{font-size:17px;font-weight:600;margin:20px 0 8px}.payment-description{font-size:11px;color:#a1adbe;margin:0 0 13px;line-height:1.8}.payment-panel .toggle-row{padding-top:11px;margin-bottom:18px}.payment-state{display:grid;grid-template-columns:1fr auto;gap:13px;font-size:10px;margin:23px 0}.payment-state dt{color:#aab5c4}.payment-state dd{margin:0;color:#8fa1b8;text-align:right;overflow-wrap:anywhere}.payment-bottom{border-top:1px solid #edf1f7;padding-top:18px;display:flex;align-items:center;justify-content:space-between;gap:10px}.settings-footer{font-size:10px;color:#a8b3c3;text-align:center;margin:25px 0 0;line-height:1.9}.back-button{display:flex;align-items:center;gap:5px;background:none;border:0;color:#88a0bf;font-size:11px;padding:0 0 18px}@media(min-width:1500px){.service-settings{padding:32px;max-width:1510px}.configuration-columns{grid-template-columns:minmax(0,1fr) 370px}.resource-table td{padding:17px 22px}}@media(max-width:1100px){.service-settings{padding:22px}.configuration-columns{grid-template-columns:minmax(0,1fr) 290px;gap:18px}.configuration-state{padding:22px}.editor-columns{grid-template-columns:minmax(0,1fr) 250px;gap:22px}.resource-history{padding-left:19px}.resource-filters{flex-wrap:wrap}.filter-search{min-width:200px}.resource-filters select{flex:1}.resource-statistics article{padding:18px}.resource-table td,.resource-table th{padding-left:15px;padding-right:15px}.settings-header p{max-width:550px}.payment-panel{padding:21px}}@media(max-width:800px){.service-settings{padding:18px;min-width:660px}.settings-header h1{font-size:22px}.header-actions{gap:7px}.resource-statistics{gap:12px}.resource-statistics article{gap:12px;padding:16px}.stat-icon{width:36px;height:36px}.resource-statistics strong{font-size:21px}.configuration-columns{grid-template-columns:minmax(0,1fr)}.configuration-state dl{grid-template-columns:1fr 1fr 1fr 1fr}.configuration-state dd{text-align:left}.editor-columns{grid-template-columns:minmax(0,1fr)}.resource-history{padding-left:0;padding-top:22px;border-left:0;border-top:1px solid #edf1f6}.form-bottom{align-items:flex-start;flex-wrap:wrap}.configuration-notice{align-items:flex-start}.configuration-notice>.status-tag{margin-top:2px}}
</style>
