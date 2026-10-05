/** Shared local-demo primitives. No network, HTML, storage or other-domain writes. */
export const ENVIRONMENT = 'SIMULATION'
export const LOCAL_ATTACHMENT_BUDGET = 1024 * 1024
export const LOCAL_DOMAIN_BUDGET = 4 * 1024 * 1024
export const clone = value => JSON.parse(JSON.stringify(value))
export const sameId = (a, b) => String(a) === String(b)
export const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value)
export function check(ok, message, code = 'VALIDATION_FAILED') {
  if (!ok) { const error = new Error(message); error.code = code; throw error }
}
export function fields(value, allowed) {
  check(isObject(value), '参数须为对象')
  check(Object.keys(value).every(key => allowed.includes(key)), '包含未允许的字段')
}
export function identity(db, input) {
  const tables = { ROLE_USER: 'user', ROLE_UNIT: 'unit', ROLE_ADMIN: 'admin' }
  const labels = { ROLE_USER: '顾客', ROLE_UNIT: '商家客服', ROLE_ADMIN: '平台客服' }
  check(isObject(db) && isObject(input) && tables[input.role] && /^(?:[1-9]\d{0,19})$/.test(String(input.id)), '请先登录有效账号', 'SUPPORT_AUTH')
  const profile = db[tables[input.role]]?.find(row => sameId(row.id, input.id))
  check(profile && profile.active !== false && profile.enabled !== false && !['DISABLED', 'INACTIVE', 'BLOCKED'].includes(profile.status), '当前账号不存在或已停用，请重新登录', 'SUPPORT_AUTH')
  const display = db.support?.supportProfiles?.[`${input.role}:${profile.id}`]
  return { id: String(profile.id), role: input.role, name: display?.nickname || profile.nickname || profile.username || labels[input.role], avatar: display?.avatarUrl || profile.avatarUrl || '/avatar.svg', label: labels[input.role] }
}
export const actorKey = actor => `${actor.role}:${actor.id}`
export function stateOf(db) {
  if (db.support == null) return { schemaVersion: 1, sequence: 6, sessions: [], messages: [], tickets: [], idempotency: [], attachments: [], resources: [], resourceHistory: [], platformConfig: {}, platformConfigHistory: [], supportProfiles: {}, audit: [] }
  const old = db.support
  check(isObject(old) && old.schemaVersion === 1 && Number.isSafeInteger(old.sequence) && old.sequence >= 0 && ['sessions', 'messages', 'tickets', 'idempotency'].every(key => Array.isArray(old[key])), '客服演示数据版本无效，请先备份再重置演示数据', 'SUPPORT_SCHEMA')
  const state = clone(old)
  for (const name of ['attachments', 'resources', 'resourceHistory', 'platformConfigHistory', 'audit']) {
    check(state[name] === undefined || Array.isArray(state[name]), '客服演示数据版本无效', 'SUPPORT_SCHEMA')
    state[name] ??= []
  }
  check(state.platformConfig === undefined || isObject(state.platformConfig), '客服配置数据无效', 'SUPPORT_SCHEMA')
  state.platformConfig ??= {}
  check(state.supportProfiles === undefined || isObject(state.supportProfiles), '客服个人资料数据无效', 'SUPPORT_SCHEMA')
  state.supportProfiles ??= {}
  // Migration is detached; queries never persist it. Existing history is not rewritten.
  for (const ticket of state.tickets) {
    if (ticket.priority === 'NORMAL') ticket.priority = 'MEDIUM'
    if (ticket.priority === 'IMPORTANT') ticket.priority = 'HIGH'
    ticket.archived ??= false
  }
  state.sequence = Math.max(state.sequence, 6)
  return state
}
export function commit(db, state) {
  check(JSON.stringify(state).length * 2 <= LOCAL_DOMAIN_BUDGET, '本机客服演示数据已达4 MiB预算，请先导出备份；不会自动删除历史', 'LOCAL_STORAGE_BUDGET_EXCEEDED')
  db.support = state
}
export function next(state) {
  check(state.sequence < Number.MAX_SAFE_INTEGER - 1, '本机客服编号已达上限', 'SUPPORT_SCHEMA')
  state.sequence++
  return { id: String(state.sequence), sequence: state.sequence }
}
export function timestamp(now) {
  check(typeof now === 'number' && Number.isFinite(now) && !Number.isNaN(new Date(now).getTime()), '当前时间无效')
  return new Date(now).toISOString()
}
export function textValue(value, limit, label, optional = false) {
  check(typeof value === 'string' || optional && value === undefined, `${label}须为文字`)
  const text = (value ?? '').replace(/\r\n?/g, '\n').trim()
  check((optional || text.length > 0) && [...text].length <= limit && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(text), `${label}请填写${optional ? '不超过' : '1至'}${limit}字的纯文本`)
  return text
}
export function literalText(value, limit, label) {
  check(typeof value === 'string' && value.trim().length > 0 && value.length <= limit && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(value), `${label}无效或超过${limit}字符`)
  return value
}
export function version(row, value) {
  check(Number.isInteger(value) && value >= 1 && value <= 100000000, '请提供1至100000000的当前版本')
  check(value === row.version, '记录已更新，请刷新后重试', 'SUPPORT_VERSION_CONFLICT')
  check(row.version < 100000000, '记录版本已达上限', 'VERSION_LIMIT_EXCEEDED')
}
export function clientKey(value) {
  check(typeof value === 'string' && value.length >= 16 && value.length <= 128 && value.trim() === value && !/[\u0000-\u001f\u007f]/.test(value), '提交标识须为16至128字符')
  return value
}
export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (isObject(value)) return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`
  return JSON.stringify(value)
}
// Synchronous SHA-256 keeps the existing synchronous supportAction contract.
export function sha256(input) {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : input
  const padded = new Uint8Array(Math.ceil((bytes.length + 9) / 64) * 64)
  padded.set(bytes); padded[bytes.length] = 128
  const view = new DataView(padded.buffer)
  view.setUint32(padded.length - 8, Math.floor(bytes.length / 0x20000000))
  view.setUint32(padded.length - 4, bytes.length * 8 >>> 0)
  const h = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19])
  const k = new Uint32Array([0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2])
  const w = new Uint32Array(64), rotr = (x, n) => x >>> n | x << (32 - n)
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4)
    for (let i = 16; i < 64; i++) { const a = w[i-15], b = w[i-2]; w[i] = w[i-16] + (rotr(a,7)^rotr(a,18)^a>>>3) + w[i-7] + (rotr(b,17)^rotr(b,19)^b>>>10) }
    let [a,b,c,d,e,f,g,z] = h
    for (let i = 0; i < 64; i++) {
      const t1 = (z + (rotr(e,6)^rotr(e,11)^rotr(e,25)) + (e&f^~e&g) + k[i] + w[i]) >>> 0
      const t2 = ((rotr(a,2)^rotr(a,13)^rotr(a,22)) + (a&b^a&c^b&c)) >>> 0
      z=g; g=f; f=e; e=d+t1>>>0; d=c; c=b; b=a; a=t1+t2>>>0
    }
    ;[a,b,c,d,e,f,g,z].forEach((x, i) => { h[i] = h[i] + x })
  }
  return Array.from(h, x => x.toString(16).padStart(8, '0')).join('')
}
export const fingerprint = value => sha256(canonical(value))
export function replay(state, actor, action, key, hash) {
  const row = state.idempotency.find(item => item.actorKey === actorKey(actor) && item.clientKey === key)
  if (row) check(row.action === action && row.fingerprint === hash, '同一提交标识的内容已变化，请使用新的提交标识', 'SUPPORT_IDEMPOTENCY_CONFLICT')
  return row
}
export function remember(state, actor, action, key, hash, resultId) {
  state.idempotency.push({ actorKey: actorKey(actor), action, clientKey: key, fingerprint: hash, resultId })
}
export function numericId(value) {
  check(typeof value === 'string' && /^[1-9]\d{0,19}$/.test(value), '资源标识须为有效数字字符串')
  return value
}
