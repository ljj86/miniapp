/**
 * Local-only resources and non-secret platform metadata. Built-ins are injected
 * source content, never invented here. No HTML, network, credentials or payments.
 * Persist only db.support after successful actions, along with the support core.
 */
import { clone, check, fields, identity, actorKey, sameId, stateOf, commit, next, timestamp, literalText, version, clientKey, fingerprint, replay, remember, numericId } from './support-domain.js'

const KINDS = ['KNOWLEDGE','MANUAL','UPDATE_LOG']
const AUDIENCES = ['CUSTOMER','MERCHANT','ALL']
const CHANNELS = ['MOCK','ALIPAY','WECHAT','BANKCARD']
const MATERIAL = ['kind','title','content','audience']
const QUERIES = {
  resources: ['kind','q','audience'], resource: ['id'], manuals: ['q','audience'], manual: ['id'], updates: ['q','audience'],
  'admin/resources': ['kind','q','audience','status'], 'resources/history': ['id'], 'platform/ai': [], 'platform/payments': [], profile: []
}
const ACTIONS = {
  'resources/create': MATERIAL, 'resources/update': ['version',...MATERIAL], 'resources/archive': ['version'],
  'platform/ai': ['version','enabled','model','welcomeMsg'], 'platform/payments': ['version','displayEnabled','label'], profile: ['version','nickname','avatarUrl','email','contactPhone']
}
const platform = actor => check(actor.role === 'ROLE_ADMIN', '仅平台账号可维护资料和非敏感平台配置', 'SUPPORT_FORBIDDEN')
function material(body) {
  const result = { kind: body.kind, title: literalText(body.title,160,'标题'), content: literalText(body.content,16000,'正文'), audience: body.audience }
  check(KINDS.includes(result.kind) && AUDIENCES.includes(result.audience), '资料类型或受众无效')
  return result
}
function dto(row) {
  const out = {}
  for (const name of ['id','kind','title','content','audience','status','version','createdAt','updatedAt','archivedAt','readOnly','source']) if (row[name] !== undefined) out[name] = row[name]
  return { ...out, environment: 'SIMULATION', simulationOnly: true, contentFormat: 'PLAIN_TEXT' }
}
function seeds(builtins) {
  const supplied = typeof builtins === 'function' ? builtins() : builtins
  check(Array.isArray(supplied) && supplied.length <= 6, '内置资料清单须为最多6条资料', 'RESOURCE_MANIFEST_INVALID')
  const seen = new Set()
  return supplied.map(raw => {
    check(raw && typeof raw === 'object' && /^[1-6]$/.test(raw.id) && !seen.has(raw.id), '内置资料编号须为不重复的1至6', 'RESOURCE_MANIFEST_INVALID')
    seen.add(raw.id)
    check(typeof raw.createdAt === 'string' && Number.isFinite(Date.parse(raw.createdAt)) && typeof raw.updatedAt === 'string' && Number.isFinite(Date.parse(raw.updatedAt)), '内置资料必须携带来源记录时间；不得自动假定发布日期', 'RESOURCE_MANIFEST_INVALID')
    const row = { ...material(raw), id: raw.id, status: 'ACTIVE', version: 1, readOnly: true, createdAt: raw.createdAt, updatedAt: raw.updatedAt }
    if (raw.source !== undefined) row.source = literalText(raw.source,160,'来源')
    if (raw.id === '6') { check(row.kind === 'UPDATE_LOG', '内置第6条应为源码记录', 'RESOURCE_MANIFEST_INVALID'); row.source = 'SOURCE_IMPLEMENTATION' }
    return dto(row)
  })
}
function get(state, builtins, id) {
  numericId(id)
  const row = state.resources.find(row => row.id === id) || builtins.find(row => row.id === id)
  check(row, '资料不存在或不可访问', 'RESOURCE_NOT_FOUND')
  return row
}
function editable(state, builtins, id) {
  const row = get(state,builtins,id)
  check(!row.readOnly, '内置资料仅供查阅，可另建资料补充', 'READ_ONLY_RESOURCE')
  check(row.status === 'ACTIVE', '已归档资料不可修改', 'STATE_CONFLICT')
  return row
}
function detail(state, builtins, actor, id, kind) {
  const row = get(state,builtins,id)
  check((row.status === 'ACTIVE' || actor.role === 'ROLE_ADMIN') && (!kind || kind === row.kind), '资料不存在或不可访问', 'RESOURCE_NOT_FOUND')
  return dto(row)
}
function ai(state) {
  const row = state.platformConfig.AI
  const base = row ? { id:'AI', version:row.version, enabled:row.enabled, model:row.model, welcomeMsg:row.welcomeMsg, updatedAt:row.updatedAt } : { id:'AI', version:1, enabled:false, model:'unconfigured', welcomeMsg:'您好，请描述您的问题。模拟客服不会调用外部AI服务。' }
  return { ...base, status:'NOT_CONNECTED', externalCallsEnabled:false, secretConfigured:false, configurationOnly:true, environment:'SIMULATION' }
}
function payment(state, id) {
  check(CHANNELS.includes(id), '支付渠道无效')
  const row = state.platformConfig[`PAYMENT_${id}`]
  const label = id === 'MOCK' ? '模拟支付' : id === 'ALIPAY' ? '支付宝（未连接）' : id === 'WECHAT' ? '微信支付（未连接）' : '银行卡（未连接）'
  const base = row ? { id, version:row.version, displayEnabled:row.displayEnabled, label:row.label, updatedAt:row.updatedAt } : { id, version:1, displayEnabled:id==='MOCK', label }
  return { ...base, status:id==='MOCK'?'READY':'NOT_CONNECTED', mode:id==='MOCK'?'MOCK':'NOT_CONNECTED', ready:id==='MOCK', connected:false, chargesEnabled:false, externalCallsEnabled:false, secretConfigured:false, configurationOnly:true, environment:'SIMULATION' }
}
function addHistory(state, actor, row, action, at, requestId) {
  const snapshot = dto(row), snapshotHash = fingerprint(snapshot)
  state.resourceHistory.push({ ...next(state), resourceId:row.id, resourceVersion:row.version, action, actorId:actor.id, actorRole:actor.role, requestId, snapshot, snapshotHash, createdAt:at, updatedAt:at, version:1, environment:'SIMULATION' })
  state.audit.push({ ...next(state), action:`SUPPORT_RESOURCE_${action}`, actorId:actor.id, actorRole:actor.role, resourceId:row.id, requestId, resourceVersion:row.version, snapshotHash, createdAt:at, environment:'SIMULATION' })
}
function addConfigHistory(state, actor, id, snapshot, at, requestId) {
  const snapshotHash = fingerprint(snapshot)
  state.platformConfigHistory.push({ ...next(state), configId:id, configVersion:snapshot.version, actorId:actor.id, actorRole:actor.role, requestId, snapshot:clone(snapshot), snapshotHash, createdAt:at, updatedAt:at, version:1, environment:'SIMULATION' })
  state.audit.push({ ...next(state), action:'SUPPORT_PLATFORM_METADATA_UPDATED', actorId:actor.id, actorRole:actor.role, configId:id, configVersion:snapshot.version, requestId, snapshotHash, externalCallsEnabled:false, createdAt:at, environment:'SIMULATION' })
}
function profileView(db, state, actor) {
  const row = state.supportProfiles[actorKey(actor)]
  const account = db[{ROLE_USER:'user',ROLE_UNIT:'unit',ROLE_ADMIN:'admin'}[actor.role]].find(row => sameId(row.id,actor.id))
  const phone = String(account.phone || '')
  const masked = /^\+?\d{7,}$/.test(phone) ? `${phone.slice(0,3)}****${phone.slice(-4)}` : '未提供'
  const display = row ? { version:row.version, nickname:row.nickname, avatarUrl:row.avatarUrl, email:row.email, contactPhone:row.contactPhone, updatedAt:row.updatedAt } : {version:1,nickname:actor.name,avatarUrl:'/avatar.svg',email:'',contactPhone:''}
  return { ...display, id:actor.id, uid:actorKey(actor), role:actor.role, username:account.username || `LOCAL-${actorKey(actor)}`, loginPhone:account.phoneMasked || masked, identityReadOnly:true, environment:'SIMULATION' }
}
function profileField(body, name, max, optional = false) {
  check(typeof body[name] === 'string', `${name}必须为文本`)
  const text = body[name].trim()
  check((optional || text.length > 0) && [...text].length <= max && !/[\p{Cc}]/u.test(text), `${name}无效或过长`)
  return text
}
/** builtins: array/factory of source-backed resource DTOs (IDs1–6), passed explicitly. */
export function supportResourceQuery(db, actorInput, resource, params = {}, builtins = []) {
  const actor = identity(db,actorInput), state = stateOf(db)
  check(QUERIES[resource], '未知资料查询', 'RESOURCE_NOT_FOUND')
  fields(params,QUERIES[resource])
  if (resource === 'profile') return profileView(db,state,actor)
  if (['admin/resources','resources/history','platform/ai','platform/payments'].includes(resource)) platform(actor)
  if (resource === 'platform/ai') return ai(state)
  if (resource === 'platform/payments') return CHANNELS.map(id => payment(state,id))
  const builtinRows = seeds(builtins)
  if (resource === 'resource' || resource === 'manual') return clone(detail(state,builtinRows,actor,params.id,resource === 'manual' ? 'MANUAL' : undefined))
  if (resource === 'resources/history') { get(state,builtinRows,params.id); return clone(state.resourceHistory.filter(row => row.resourceId === params.id)) }
  const kind = resource === 'manuals' ? 'MANUAL' : resource === 'updates' ? 'UPDATE_LOG' : params.kind
  check(kind === undefined || KINDS.includes(kind), '资料类型无效')
  check(params.audience === undefined || AUDIENCES.includes(params.audience), '资料受众无效')
  check(params.status === undefined || ['ACTIVE','ARCHIVED'].includes(params.status), '资料状态无效')
  if (params.q !== undefined) literalText(params.q,100,'搜索词')
  const search = params.q?.toLowerCase()
  return [...builtinRows,...state.resources].filter(row => (resource === 'admin/resources' || row.status === 'ACTIVE') && (!kind || kind === row.kind) && (!params.audience || row.audience === params.audience || row.audience === 'ALL') && (!params.status || row.status === params.status) && (search === undefined || `${row.title}\n${row.content}`.toLowerCase().includes(search))).sort((a,b) => b.updatedAt.localeCompare(a.updatedAt) || Number(b.id)-Number(a.id)).map(row => clone(dto(row)))
}
/** params carries route query only (id for update/archive/payment), not request body. */
export function supportResourceAction(db, actorInput, action, payload = {}, params = {}, builtins = [], now = Date.now()) {
  const actor = identity(db,actorInput)
  if (action !== 'profile') platform(actor)
  check(ACTIONS[action], '未知资料操作', 'RESOURCE_NOT_FOUND')
  fields(payload,[...ACTIONS[action],'clientKey'])
  fields(params,['resources/update','resources/archive','platform/payments'].includes(action)?['id']:[])
  if (['resources/update','resources/archive'].includes(action)) numericId(params.id)
  if (action === 'platform/payments') check(CHANNELS.includes(params.id), '支付渠道无效')
  const state = stateOf(db), at = timestamp(now), key = clientKey(payload.clientKey)
  const body = { ...payload }; delete body.clientKey
  const hash = fingerprint({ body, params }), operation = `resource:${action}`, previous = replay(state,actor,operation,key,hash)
  if (previous) {
    if (action === 'profile') return profileView(db,state,actor)
    if (action === 'platform/ai') return ai(state)
    if (action === 'platform/payments') return payment(state,previous.resultId)
    return detail(state,seeds(builtins),actor,previous.resultId)
  }
  let result
  if (action === 'profile') {
    const current = profileView(db,state,actor); version(current,body.version)
    const nickname = profileField(body,'nickname',80), avatarUrl = profileField(body,'avatarUrl',120), email = profileField(body,'email',254,true), contactPhone = profileField(body,'contactPhone',32,true)
    check(avatarUrl === '/avatar.svg', '头像必须是受控的本站资源')
    check(!email || /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}$/.test(email), '邮箱格式无效')
    check(!contactPhone || /^[+0-9 ()-]{3,32}$/.test(contactPhone), '联系号码格式无效')
    state.supportProfiles[actorKey(actor)] = { version:current.version+1, nickname, avatarUrl, email, contactPhone, updatedAt:at }
    state.audit.push({ ...next(state), action:'support.profile.update', actorId:actor.id, actorRole:actor.role, fields:['nickname','avatarUrl','email','contactPhone'], version:current.version+1, requestId:key, createdAt:at, environment:'SIMULATION' })
    result = profileView(db,state,actor)
  } else if (action === 'platform/ai') {
    const current = ai(state); version(current,body.version)
    check(typeof body.enabled === 'boolean', 'enabled必须为布尔值')
    const model = literalText(body.model,80,'模型名称'), welcomeMsg = literalText(body.welcomeMsg,500,'欢迎语')
    check(/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}$/.test(model) && !/^sk-/i.test(model), 'model应为模型名称，不能是地址或密钥')
    state.platformConfig.AI = { id:'AI', version:current.version+1, enabled:body.enabled, model, welcomeMsg, updatedAt:at }
    result = ai(state); addConfigHistory(state,actor,'AI',result,at,key)
  } else if (action === 'platform/payments') {
    const current = payment(state,params.id); version(current,body.version)
    check(typeof body.displayEnabled === 'boolean', 'displayEnabled必须为布尔值')
    const label = literalText(body.label,80,'渠道名称')
    state.platformConfig[`PAYMENT_${params.id}`] = { id:params.id, version:current.version+1, displayEnabled:body.displayEnabled, label, updatedAt:at }
    result = payment(state,params.id); addConfigHistory(state,actor,`PAYMENT_${params.id}`,result,at,key)
  } else {
    const builtinRows = seeds(builtins)
    if (action === 'resources/create') {
      const materials = material(body)
      check(state.resources.length < 500, '模拟资料最多保存500条（含归档）', 'RESOURCE_QUOTA_EXCEEDED')
      const row = { ...next(state), ...materials, status:'ACTIVE', readOnly:false, version:1, creatorId:actor.id, createdAt:at, updatedAt:at }
      state.resources.push(row); addHistory(state,actor,row,'CREATED',at,key); result = dto(row)
    } else {
      const row = editable(state,builtinRows,params.id); version(row,body.version)
      if (action === 'resources/update') {
        const materials = material(body)
        check(MATERIAL.some(name => row[name] !== materials[name]), '资料内容未发生变化', 'MATERIAL_UNCHANGED')
        Object.assign(row,materials); row.version++; row.updatedAt = at
        addHistory(state,actor,row,'UPDATED',at,key)
      } else {
        row.status = 'ARCHIVED'; row.archivedAt = at; row.updatedAt = at; row.version++
        addHistory(state,actor,row,'ARCHIVED',at,key)
      }
      result = dto(row)
    }
  }
  remember(state,actor,operation,key,hash,result.id)
  const detached = clone(result)
  commit(db,state)
  return detached
}
