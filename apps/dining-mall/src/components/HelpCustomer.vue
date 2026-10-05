<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Document, Download, Refresh, Search } from '@element-plus/icons-vue'
import request from '@/utils/request'

const props = defineProps({ mode: { type: String, default: 'manuals' }, resourceId: { type: [String, Number], default: '' } })
const route = useRoute(), router = useRouter()
const records = ref([]), manual = ref(null), loading = ref(false), error = ref(''), query = ref(''), audience = ref('')
const covers = ref({}), preview = ref(''), previewError = ref(''), previewLoading = ref(false), page = ref(1)
const downloading = ref(false), downloadError = ref(''), textVisible = ref(false), listPage = ref(1)
const PAGE_SIZE = 6
let disposed = false, generation = 0, previewGeneration = 0
const ownedUrls = new Set(), downloadTimers = new Set()
const id = computed(() => String(props.resourceId || route.query.id || ''))
const isManual = computed(() => props.mode === 'manual')
const title = computed(() => ({ manuals: '使用手册', manual: '手册阅读', updates: '更新记录', materials: '帮助资料' }[props.mode] || '帮助资料'))
const audienceLabels = { CUSTOMER: '顾客', MERCHANT: '商家', ALL: '全部用户' }
const kindLabels = { MANUAL: '使用手册', KNOWLEDGE: '知识资料', UPDATE_LOG: '更新记录' }
const snapshot = () => `${generation}|${props.mode}|${id.value}|${route.fullPath}|${sessionStorage.getItem('account') || ''}`
const current = stamp => !disposed && stamp === snapshot()
const date = value => { const d = new Date(value); return value && !Number.isNaN(d.getTime()) ? d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }) : '日期未提供' }
const pageAssets = value => (Array.isArray(value?.availableAssets) ? value.availableAssets : []).filter(asset => asset.format === 'PNG' && Number.isInteger(asset.page) && asset.page > 0).sort((a, b) => a.page - b.page)
const pages = computed(() => pageAssets(manual.value))
const activePage = computed(() => pages.value.find(asset => asset.page === page.value))
const pdfAsset = computed(() => (manual.value?.availableAssets || []).find(asset => asset.format === 'PDF'))
const pageCount = computed(() => Math.max(1, Math.ceil(records.value.length / PAGE_SIZE)))
const visibleRecords = computed(() => records.value.slice((listPage.value - 1) * PAGE_SIZE, listPage.value * PAGE_SIZE))
const availablePageCount = row => pageAssets(row).length
const builtIn = row => row?.readOnly && ['1', '2', '3'].includes(String(row.id))
const summary = row => String(row.content || '').replace(/\s+/g, ' ').slice(0, 78)

function unpack(response) {
  if (!response || response.error || (response.code !== undefined && String(response.code) !== '200')) {
    const e = new Error(response?.msg || response?.error?.message || response?.message || '暂时无法读取资料，请稍后重试')
    e.code = response?.error?.code || response?.errorCode || response?.code
    throw e
  }
  return response.data
}
function describe(e) { return e?.response?.data?.error?.message || e?.response?.data?.msg || e?.message || '网络异常，请重试' }
function keepUrl(blob) { const url = URL.createObjectURL(blob); ownedUrls.add(url); return url }
function release(url) { if (ownedUrls.delete(url)) URL.revokeObjectURL(url) }
function releaseAll() { for (const url of [...ownedUrls]) release(url); for (const timer of downloadTimers) clearTimeout(timer); downloadTimers.clear() }
function invalidate() { generation++; previewGeneration++; releaseAll(); covers.value = {}; preview.value = ''; downloading.value = false; downloadError.value = ''; previewLoading.value = false }

async function assetBlob(data, descriptor, format) {
  const expectedMime = format === 'PDF' ? 'application/pdf' : 'image/png'
  if (!data || data.mime !== expectedMime || descriptor?.mime !== expectedMime || typeof data.base64 !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data.base64) || !data.base64 || data.base64.length > 11184812) throw new Error('手册文件格式无效，已停止打开')
  const binary = atob(data.base64), bytes = Uint8Array.from(binary, character => character.charCodeAt(0))
  if (!bytes.length || bytes.length > 8 * 1024 * 1024 || (descriptor.size !== undefined && bytes.length !== descriptor.size) || (data.size !== undefined && bytes.length !== data.size)) throw new Error('手册文件大小校验失败，请重试')
  const signature = format === 'PDF' ? [37, 80, 68, 70, 45] : [137, 80, 78, 71, 13, 10, 26, 10]
  if (!signature.every((byte, index) => bytes[index] === byte)) throw new Error('手册文件类型校验失败')
  if (descriptor.sha256 && data.sha256 && descriptor.sha256 !== data.sha256) throw new Error('手册文件已变化，请重新载入')
  if (descriptor.sha256 && globalThis.crypto?.subtle) {
    const digest = await crypto.subtle.digest('SHA-256', bytes)
    const hash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
    if (hash !== descriptor.sha256) throw new Error('手册文件校验失败，请重试')
  }
  return new Blob([bytes], { type: expectedMime })
}
async function getAsset(resource, descriptor) {
  const params = { id: String(resource.id), format: descriptor.format }
  if (descriptor.format === 'PNG') params.page = descriptor.page
  const data = unpack(await request.get('/support/manual-asset', { params }))
  const blob = await assetBlob(data, descriptor, descriptor.format)
  return { blob, name: String(data.name || descriptor.name || `manual-${resource.id}.pdf`) }
}
async function loadCovers(rows, stamp) {
  await Promise.all(rows.filter(row => pageAssets(row).length).map(async row => {
    try {
      const result = await getAsset(row, pageAssets(row)[0])
      if (current(stamp)) covers.value = { ...covers.value, [row.id]: keepUrl(result.blob) }
    } catch { /* The readable text cover remains available when page assets fail. */ }
  }))
}
async function load() {
  invalidate()
  const stamp = snapshot()
  loading.value = true; error.value = ''; records.value = []; manual.value = null; previewError.value = ''; page.value = 1; listPage.value = 1; textVisible.value = false
  try {
    if (isManual.value) {
      if (!/^[1-9]\d*$/.test(id.value)) throw new Error('手册编号无效，请返回目录重新选择')
      const value = unpack(await request.get('/support/manual', { params: { id: id.value } }))
      if (!current(stamp)) return
      if (!value || value.kind !== 'MANUAL') throw new Error('没有找到这份手册')
      manual.value = value
      page.value = pageAssets(value)[0]?.page || 1
      if (pageAssets(value).length) await loadPreview()
    } else {
      const path = props.mode === 'manuals' ? '/support/manuals' : props.mode === 'updates' ? '/support/updates' : '/support/resources'
      const params = {}
      if (query.value.trim()) params.q = query.value.trim()
      if (audience.value) params.audience = audience.value
      const rows = unpack(await request.get(path, { params }))
      if (!current(stamp)) return
      if (!Array.isArray(rows)) throw new Error('资料响应格式不正确，请重试')
      records.value = rows
      if (props.mode === 'manuals') void loadCovers(rows, stamp)
    }
  } catch (e) { if (current(stamp)) error.value = describe(e) }
  finally { if (current(stamp)) loading.value = false }
}
async function loadPreview() {
  const descriptor = activePage.value, resource = manual.value, stamp = snapshot(), operation = ++previewGeneration
  release(preview.value); preview.value = ''; previewError.value = ''
  if (!descriptor || !resource) return
  previewLoading.value = true
  try {
    const result = await getAsset(resource, descriptor)
    if (current(stamp) && operation === previewGeneration) preview.value = keepUrl(result.blob)
  } catch (e) { if (current(stamp) && operation === previewGeneration) previewError.value = describe(e) }
  finally { if (current(stamp) && operation === previewGeneration) previewLoading.value = false }
}
function selectPage(value) { if (value === page.value || !pages.value.some(asset => asset.page === value)) return; page.value = value; void loadPreview() }
function stepPage(offset) { const index = pages.value.findIndex(asset => asset.page === page.value); const target = pages.value[index + offset]; if (target) selectPage(target.page) }
function saveBlob(blob, name) {
  const url = keepUrl(blob), anchor = document.createElement('a')
  anchor.href = url; anchor.download = name.replace(/[\\/\u0000-\u001f\u007f]/g, '_'); anchor.style.display = 'none'; document.body.appendChild(anchor); anchor.click(); anchor.remove()
  const timer = setTimeout(() => { release(url); downloadTimers.delete(timer) }, 30000); downloadTimers.add(timer)
}
async function downloadPdf() {
  if (downloading.value || !pdfAsset.value || !manual.value) return
  const stamp = snapshot(), resource = manual.value, descriptor = pdfAsset.value
  downloading.value = true; downloadError.value = ''
  try { const result = await getAsset(resource, descriptor); if (current(stamp)) saveBlob(result.blob, result.name) }
  catch (e) { if (current(stamp)) downloadError.value = describe(e) }
  finally { if (current(stamp)) downloading.value = false }
}
function downloadText() {
  if (!manual.value) return
  saveBlob(new Blob([`${manual.value.title}\n\n${manual.value.content}`], { type: 'text/plain;charset=utf-8' }), `${manual.value.title}.txt`)
}
function openManual(row) { router.push({ path: '/mall/manual', query: { id: String(row.id) } }) }
function moveList(value) { listPage.value = Math.min(pageCount.value, Math.max(1, value)); document.querySelector('.native-app')?.scrollTo({ top: 0, behavior: 'smooth' }) }
watch(() => [props.mode, props.resourceId, route.fullPath], load, { immediate: true })
onBeforeUnmount(() => { disposed = true; invalidate() })
</script>

<template>
  <section class="help-customer" :data-help-mode="mode" :aria-busy="loading">
    <button v-if="isManual" class="help-back" data-testid="manual-back" @click="router.push('/mall/manuals')"><ArrowLeft /> 返回手册目录</button>
    <header v-else class="help-heading">
      <span class="help-eyebrow">鲜食好店 · 帮助中心</span><h1>{{ title }}</h1>
      <p>{{ mode === 'manuals' ? '选一本手册，从第一步开始' : mode === 'updates' ? '查看资料中的变更说明与记录日期' : '常见问题与使用说明，随时查阅' }}</p>
      <nav class="help-nav" aria-label="帮助分类"><button :class="{ active: mode === 'manuals' }" @click="router.push('/mall/manuals')">使用手册</button><button :class="{ active: mode === 'materials' }" @click="router.push('/mall/materials')">帮助资料</button><button :class="{ active: mode === 'updates' }" @click="router.push('/mall/updates')">更新记录</button></nav>
      <form class="help-search" @submit.prevent="load"><Search /><input v-model="query" aria-label="搜索帮助资料" maxlength="100" placeholder="搜索标题或内容" /><button type="submit" :disabled="loading">搜索</button></form>
      <div class="audience-filter"><label for="help-audience">适用对象</label><select id="help-audience" v-model="audience" @change="load"><option value="">全部资料</option><option value="CUSTOMER">顾客</option><option value="MERCHANT">商家</option></select><span v-if="!loading && !error">{{ records.length }} 份资料</span></div>
    </header>
    <div v-if="error" class="help-state is-error" role="alert"><Document /><h2>暂时无法打开</h2><p>{{ error }}</p><button class="help-primary" data-testid="help-retry" @click="load"><Refresh /> 重试</button></div>
    <div v-else-if="loading" class="help-state" role="status"><span class="help-spinner"></span><p>正在读取{{ isManual ? '手册' : '资料' }}…</p></div>
    <template v-else-if="isManual && manual">
      <header class="manual-heading"><div><span class="help-eyebrow">{{ audienceLabels[manual.audience] || '使用指南' }} · {{ builtIn(manual) ? '内置指南' : '文字手册' }}</span><h1>{{ manual.title }}</h1></div><p>{{ pages.length ? `${pages.length} 页图文手册` : '纯文字内容' }}<span> · 版本 {{ manual.version }}</span></p></header>
      <div class="manual-actions"><button v-if="pdfAsset" class="help-primary" :disabled="downloading" data-testid="manual-download-pdf" @click="downloadPdf"><Download /> {{ downloading ? '正在准备…' : '下载 PDF' }}</button><button v-else class="help-primary" data-testid="manual-download-txt" @click="downloadText"><Download /> 下载 TXT</button><button v-if="pages.length" class="help-secondary" @click="textVisible = !textVisible">{{ textVisible ? '收起文字' : '查看文字版' }}</button></div>
      <p v-if="downloadError" class="inline-error" role="alert">{{ downloadError }}</p>
      <template v-if="pages.length">
        <div class="manual-preview" :aria-busy="previewLoading">
          <div v-if="previewLoading" class="preview-state" role="status"><span class="help-spinner"></span><span>正在读取第 {{ page }} 页…</span></div>
          <div v-else-if="previewError" class="preview-state" role="alert"><Document /><p>{{ previewError }}</p><button class="help-secondary" data-testid="manual-page-retry" @click="loadPreview">重新读取本页</button></div>
          <img v-else-if="preview" :key="preview" :src="preview" :alt="`${manual.title} · 第 ${page} 页`" data-testid="manual-page-image" @error="previewError = '本页图片未能显示，可重试或查看文字版'" />
        </div>
        <nav class="manual-pagination" aria-label="手册页码" data-testid="manual-pagination"><button :disabled="page === pages[0]?.page" aria-label="上一页" data-testid="manual-prev" @click="stepPage(-1)"><ArrowLeft /></button><button v-for="asset in pages" :key="asset.page" :aria-current="page === asset.page ? 'page' : undefined" :class="{ active: page === asset.page }" :aria-label="`第 ${asset.page} 页`" @click="selectPage(asset.page)">{{ asset.page }}</button><button :disabled="page === pages[pages.length - 1]?.page" aria-label="下一页" data-testid="manual-next" @click="stepPage(1)"><ArrowRight /></button><span>{{ page }} / {{ pages.length }}</span></nav>
      </template>
      <article v-if="!pages.length || textVisible" class="manual-text" data-testid="manual-text"><span v-if="builtIn(manual) && !pages.length" class="text-fallback">图文附件暂不可用，仍可阅读文字内容</span><p>{{ manual.content }}</p></article>
      <p class="help-footnote">按资料内容使用相关功能；当前服务为模拟环境</p>
    </template>
    <div v-else-if="!records.length" class="help-state"><Document /><h2>{{ query || audience ? '没有找到相关资料' : '还没有可查看的资料' }}</h2><p>{{ query || audience ? '试试其他关键词或适用对象' : '资料添加后会显示在这里' }}</p><button v-if="query || audience" class="help-secondary" @click="query = ''; audience = ''; load()">查看全部资料</button></div>
    <template v-else>
      <div v-if="mode === 'manuals'" class="manual-catalog" data-testid="manual-catalog"><button v-for="row in visibleRecords" :key="row.id" class="manual-card" :data-manual-id="row.id" @click="openManual(row)"><div class="manual-cover" :class="`cover-${Number(row.id) % 3}`"><img v-if="covers[row.id]" :src="covers[row.id]" :alt="`${row.title}封面`" /><template v-else><span>鲜食好店</span><Document /><strong>{{ row.title }}</strong><small>{{ availablePageCount(row) ? `${availablePageCount(row)} 页` : '文字指南' }}</small></template></div><div class="manual-card-copy"><span class="resource-tag">{{ audienceLabels[row.audience] || '使用指南' }}</span><h2>{{ row.title }}</h2><p>{{ summary(row) }}</p><div class="manual-card-bottom"><span>{{ availablePageCount(row) ? `${availablePageCount(row)} 页 · PDF / 图文` : '纯文字 · TXT' }}</span><span>阅读 <ArrowRight /></span></div></div></button></div>
      <ol v-else-if="mode === 'updates'" class="update-timeline" data-testid="update-timeline"><li v-for="row in visibleRecords" :key="row.id"><time :datetime="row.updatedAt || row.createdAt">{{ date(row.updatedAt || row.createdAt) }}</time><article><div class="update-labels"><span>{{ row.source === 'SOURCE_IMPLEMENTATION' ? '源码实现说明' : '更新说明' }}</span><small>版本 {{ row.version }}</small></div><h2>{{ row.title }}</h2><p v-if="row.source === 'SOURCE_IMPLEMENTATION'" class="source-note">此条目记录源码实现范围，不表示已经发布或部署</p><p class="plain-content">{{ row.content }}</p></article></li></ol>
      <div v-else class="resource-list" data-testid="resource-list"><article v-for="row in visibleRecords" :key="row.id" class="resource-card"><div><span class="resource-tag">{{ kindLabels[row.kind] || '帮助资料' }}</span><small>{{ audienceLabels[row.audience] }}</small></div><h2>{{ row.title }}</h2><p v-if="row.source === 'SOURCE_IMPLEMENTATION'" class="source-note">源码实现说明，不代表发布或部署</p><p class="plain-content">{{ row.content }}</p><button v-if="row.kind === 'MANUAL'" class="help-secondary" @click="openManual(row)">打开手册 <ArrowRight /></button><span class="resource-date">记录日期 {{ date(row.updatedAt || row.createdAt) }}</span></article></div>
      <nav v-if="pageCount > 1" class="list-pagination" aria-label="资料列表页码"><button :disabled="listPage === 1" @click="moveList(listPage - 1)">上一页</button><span>{{ listPage }} / {{ pageCount }}</span><button :disabled="listPage === pageCount" @click="moveList(listPage + 1)">下一页</button></nav>
      <p class="help-footnote">{{ records.length }} 份资料 · 内容以当前返回记录为准</p>
    </template>
  </section>
</template>

<style scoped>
.help-customer{padding:20px 18px 30px;min-width:0;color:#273c36;box-sizing:border-box;font-family:inherit}.help-customer *{box-sizing:border-box}.help-customer button,.help-customer input,.help-customer select{font:inherit}.help-customer button{cursor:pointer}.help-customer button:disabled{opacity:.4;cursor:not-allowed}.help-customer button:focus-visible,.help-customer input:focus-visible,.help-customer select:focus-visible{outline:2px solid #2d8668;outline-offset:3px}.help-customer svg{width:17px;height:17px;flex-shrink:0}.help-heading h1,.manual-heading h1{font-size:23px;line-height:1.5;letter-spacing:-.5px;margin:7px 0 6px;overflow-wrap:anywhere}.help-eyebrow{font-size:10px;color:#81938a;letter-spacing:1px}.help-heading>p{font-size:12px;color:#8a9991;line-height:1.8;margin:0}.help-nav{display:flex;gap:22px;border-bottom:1px solid #e6ece7;margin:21px 0 18px}.help-nav button{position:relative;padding:0 0 12px;border:0;background:none;color:#8a9991;font-size:12px}.help-nav button.active{color:#267355;font-weight:650}.help-nav button.active:after{content:'';position:absolute;height:3px;width:20px;bottom:-1px;background:#388060;border-radius:3px;left:calc(50% - 10px)}.help-search{display:flex;gap:9px;align-items:center;padding:9px 11px;border:1px solid #e4ebe4;background:#fff;border-radius:10px}.help-search svg{color:#a1aea5}.help-search input{flex:1;min-width:0;border:0;outline:none;background:none;font-size:12px;color:#35483e}.help-search button{border:0;background:none;color:#397c5b;font-size:11px}.audience-filter{display:flex;align-items:center;gap:9px;font-size:10px;color:#8b9a90;margin:14px 0 19px}.audience-filter select{padding:5px 7px;border:1px solid #e3eae3;border-radius:5px;background:white;color:#667e6c;font-size:10px}.audience-filter>span{margin-left:auto}.help-state{min-height:230px;padding:38px 13px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;gap:10px}.help-state>svg{width:37px;height:37px;color:#adc1b2}.help-state h2{font-size:15px;font-weight:600;margin:4px 0 0}.help-state p{font-size:12px;color:#8c9b90;line-height:1.8;margin:0;overflow-wrap:anywhere}.help-state.is-error p{color:#aa715b}.help-spinner{display:block;width:22px;height:22px;border:2px solid #dce8df;border-top-color:#518363;border-radius:50%;animation:help-spin .85s linear infinite}@keyframes help-spin{to{transform:rotate(360deg)}}.help-primary,.help-secondary{display:inline-flex;justify-content:center;align-items:center;gap:6px;padding:9px 13px;border-radius:8px;font-size:11px;border:1px solid #dfe9e1;text-decoration:none}.help-primary{background:#317859;border-color:#317859;color:white}.help-secondary{background:#fff;color:#5c7c66}.help-back{display:flex;align-items:center;gap:5px;padding:0;background:none;border:0;color:#788c7d;font-size:11px}.manual-heading{margin:21px 0 15px}.manual-heading h1{font-size:20px}.manual-heading>p{color:#93a194;font-size:10px;margin-top:7px}.manual-actions{display:flex;flex-wrap:wrap;gap:9px;margin-bottom:18px}.manual-preview{border:1px solid #e0e7df;border-radius:8px;overflow:hidden;background:#fff;box-shadow:0 7px 22px #27423708}.manual-preview img{display:block;width:100%;height:auto}.preview-state{display:flex;flex-direction:column;justify-content:center;align-items:center;gap:15px;min-height:300px;padding:25px;text-align:center;color:#8a9d8e;font-size:12px;line-height:1.8}.preview-state p{margin:0;overflow-wrap:anywhere}.manual-pagination{display:flex;align-items:center;justify-content:center;gap:8px;margin:18px 0}.manual-pagination button{display:flex;align-items:center;justify-content:center;min-width:32px;height:32px;background:#fff;border:1px solid #dfe8df;color:#809482;border-radius:7px;font-size:12px}.manual-pagination button.active{background:#317859;border-color:#317859;color:#fff}.manual-pagination>span{color:#8a9d8f;font-size:10px;margin-left:4px}.manual-text{background:#fff;border:1px solid #e5eae3;border-radius:10px;padding:18px;margin-top:12px}.manual-text p,.plain-content{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.95;color:#697d6d;margin:0}.text-fallback{display:block;background:#f5f7ef;color:#8d9270;padding:9px;font-size:10px;line-height:1.8;margin-bottom:14px}.inline-error{padding:10px 12px;background:#fff3ec;border:1px solid #f2ded1;color:#a5765c;border-radius:6px;font-size:11px;line-height:1.7}.manual-catalog{display:flex;flex-direction:column;gap:14px}.manual-card{display:flex;gap:14px;width:100%;padding:14px;background:#fff;border:1px solid #e6ebe3;border-radius:13px;color:inherit;text-align:left;box-shadow:0 3px 13px #29432603}.manual-cover{position:relative;flex-shrink:0;width:89px;height:124px;display:flex;flex-direction:column;align-items:flex-start;overflow:hidden;background:#e9efe0;border:1px solid #e1e6d8;border-radius:5px;padding:10px;color:#688065}.manual-cover>img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:top}.manual-cover>span{font-size:6px;letter-spacing:1px}.manual-cover>svg{margin:12px 0 7px;width:22px;height:22px;opacity:.65}.manual-cover>strong{font-size:10px;line-height:1.6;overflow-wrap:anywhere}.manual-cover>small{font-size:7px;margin-top:auto}.cover-1{background:#f1ecdd;border-color:#eae4d1;color:#95805b}.cover-2{background:#e7eeed;border-color:#dfe6e6;color:#658589}.manual-card-copy{min-width:0;display:flex;flex-direction:column;flex:1;padding-top:2px}.resource-tag{display:inline-block;font-size:9px;line-height:1.4;color:#709478;background:#eff5ed;padding:3px 6px;border-radius:4px;align-self:flex-start}.manual-card h2{font-size:13px;line-height:1.55;margin:9px 0 7px;overflow-wrap:anywhere}.manual-card p{font-size:10px;color:#9aaa9b;line-height:1.65;margin:0;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.manual-card-bottom{display:flex;justify-content:space-between;align-items:center;gap:5px;margin-top:auto;padding-top:13px;font-size:9px;color:#9caa9d}.manual-card-bottom>span:last-child{display:flex;align-items:center;white-space:nowrap;color:#64876b}.manual-card-bottom svg{width:12px;height:12px}.help-footnote{color:#adb8ad;text-align:center;font-size:9px;line-height:1.8;margin:24px 0 0}.update-timeline{list-style:none;padding:0 0 0 14px;margin:20px 0 0;border-left:1px solid #dbe6d9}.update-timeline>li{position:relative;padding:0 0 25px 6px}.update-timeline>li:before{content:'';position:absolute;left:-19px;top:5px;width:7px;height:7px;border:2px solid #f8faf5;border-radius:50%;background:#77997b}.update-timeline time{display:block;color:#94a390;font-size:10px;margin:0 0 9px}.update-timeline article,.resource-card{border:1px solid #e5eae1;border-radius:11px;background:white;padding:16px}.update-labels{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:9px;color:#74937b}.update-labels small{color:#9eac9d;font-size:9px}.update-timeline h2,.resource-card h2{font-size:14px;line-height:1.6;margin:10px 0;overflow-wrap:anywhere}.source-note{font-size:10px!important;color:#a18a62!important;background:#faf7ec;border-radius:5px;padding:9px;line-height:1.8!important;margin-bottom:12px!important}.resource-list{display:flex;flex-direction:column;gap:14px}.resource-card>div{display:flex;justify-content:space-between;align-items:center}.resource-card small{font-size:9px;color:#a5b0a2}.resource-card .help-secondary{margin-top:14px}.resource-date{display:block;font-size:9px;color:#a9b4a6;margin-top:16px}.list-pagination{display:flex;justify-content:center;align-items:center;gap:17px;margin:22px 0 0;font-size:11px;color:#839781}.list-pagination button{border:1px solid #e0e8dc;border-radius:6px;background:#fff;padding:7px 10px;color:#6c856c}@media(max-width:360px){.help-customer{padding:17px 13px}.manual-card{gap:11px;padding:11px}.manual-cover{width:76px;height:108px}.manual-card-bottom{font-size:8px}.help-nav{gap:20px}}
</style>
