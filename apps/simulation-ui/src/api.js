/** In-memory only: no tokens or credential values are persisted or logged. */
export class ApiError extends Error {
  constructor(message, {status = 0, code = 'NETWORK_ERROR', requestId, retryable = false} = {}) {
    super(message); Object.assign(this, {status, code, requestId, retryable})
  }
}
export function requestKey() { return `sim-ui-${globalThis.crypto.randomUUID()}` }
export function unwrap(payload) {
  if (payload?.code !== undefined && payload.code !== 0) throw new ApiError(payload.message || payload.msg || '请求失败', {code: payload.code, requestId: payload.requestId})
  return payload && Object.hasOwn(payload, 'data') ? payload.data : payload
}
export function safePath(template, params = {}) {
  return template.replace(/\{([^}]+)\}/g, (_, key) => {
    if (params[key] === undefined || String(params[key]).trim() === '') throw new ApiError(`请填写路径参数 ${key}`, {code:'INPUT_REQUIRED'})
    return encodeURIComponent(String(params[key]))
  })
}
export function parseMinor(value) {
  if (!/^\d+$/.test(String(value)) || !Number.isSafeInteger(Number(value)) || Number(value) < 1) throw new ApiError('金额必须是大于 0 的整数分', {code:'INPUT_INVALID'})
  return Number(value)
}
export function money(value) { return Number.isSafeInteger(value) ? `¥${(value / 100).toLocaleString('zh-CN', {minimumFractionDigits:2, maximumFractionDigits:2})}` : '未读取' }
export function redact(value) {
  if (Array.isArray(value)) return value.map(redact)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, v]) => [key, /token|credential|signature|challengeId|^code$/i.test(key) ? '[仅内存保留]' : redact(v)]))
  return value
}
export function createClient({fetchImpl = globalThis.fetch, timeoutMs = 20000} = {}) {
  const inflight = new Map()
  async function request(path, {method='GET', body, token, key, query, binary=false, signal}={}) {
    if (!path.startsWith('/api/v1/') || path.includes('://')) throw new ApiError('仅允许同源模拟 API', {code:'INVALID_DESTINATION'})
    const params = new URLSearchParams(Object.entries(query || {}).filter(([, v]) => v !== '' && v != null))
    const url=path + (params.size ? `${path.includes('?') ? '&' : '?'}${params}` : '')
    const headers={}; if (token) headers.Authorization=`Bearer ${token}`
    if (key) headers['Idempotency-Key']=key
    const multipart=typeof FormData!=='undefined' && body instanceof FormData
    if (body!==undefined && !multipart) headers['Content-Type']='application/json'
    const controller=new AbortController(), timer=setTimeout(()=>controller.abort(), timeoutMs)
    const abort=()=>controller.abort(); signal?.addEventListener('abort',abort,{once:true})
    try {
      const response=await fetchImpl(url, {method,headers,body:body===undefined?undefined:multipart?body:JSON.stringify(body),signal:controller.signal})
      if (binary && response.ok) return {data:await response.blob(),meta:{},status:response.status}
      let payload; try { payload=await response.json() } catch { throw new ApiError('服务返回了无法读取的内容', {status:response.status,code:'INVALID_RESPONSE'}) }
      if (!response.ok) throw new ApiError(payload.message || payload.msg || `请求失败（${response.status}）`, {status:response.status,code:payload.code || 'HTTP_ERROR',requestId:payload.requestId || payload.meta?.requestId,retryable:response.status>=500})
      return {data:unwrap(payload),meta:payload.meta||{},status:response.status}
    } catch(error) {
      if (error instanceof ApiError) throw error
      throw new ApiError(error.name==='AbortError'?'请求超时，结果可能已提交。请使用原请求重试确认。':'网络连接中断，结果未知。请使用原请求重试确认。',{retryable:true})
    } finally {clearTimeout(timer);signal?.removeEventListener('abort',abort)}
  }
  function once(identity, path, options) {
    if (inflight.has(identity)) return inflight.get(identity)
    const pending=request(path,options).finally(()=>inflight.delete(identity));inflight.set(identity,pending);return pending
  }
  return {request,once}
}
export function errorHint(error) {
  if (/VERSION|SNAPSHOT|CONFLICT/.test(String(error.code))) return '数据或状态已变化。请刷新当前记录，再检查内容后提交。不要重复发送旧版本。'
  if (error.status===401) return '会话失效，请重新登录所选测试身份。'
  if (error.status===403) return '该身份没有此记录的操作范围。请切换到有权限的独立角色。'
  if (error.status===429) return '请求频率已受限，请稍后再操作。'
  return error.retryable?'重试会复用原正文、原身份及幂等键；不要改用新请求重复提交。':''
}
