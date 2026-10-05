/**
 * Local browser demonstration, not production authentication or secure storage.
 * Actions validate on a clone, then replace only db.support. Caller persists only
 * after success. Text is literal: render by interpolation, never HTML. No external
 * requests, payments, credits or order changes occur here.
 */
import { clone, check, fields, identity, actorKey, stateOf, commit, next, timestamp, sameId, textValue, version, clientKey, fingerprint, replay, remember, numericId, LOCAL_ATTACHMENT_BUDGET, LOCAL_DOMAIN_BUDGET } from './support-domain.js'
import { uploadMaterial, upload, metadata, download, attachmentIds, readableAttachment, bind } from './support-attachments.js'

const STATUSES = ['OPEN','IN_PROGRESS','RESOLVED','CLOSED']
const ACTIONS = {
  'open-session': ['unitId'], 'send-message': ['sessionId','text','attachmentIds'],
  'mark-read': ['sessionId','lastSeenMessageId'],
  'create-ticket': ['unitId','title','content','priority','orderId','attachmentIds'],
  'reply-ticket': ['ticketId','text','version','attachmentIds'],
  'ticket-status': ['ticketId','status','version'],
  'archive-ticket': ['ticketId','version','reason'], 'restore-ticket': ['ticketId','version','reason'],
  'attachments/upload': ['name','mime','base64']
}
const QUERY = { context: [], overview: ['q','unitId','status','priority','includeArchived','page','limit'], session: ['id','beforeId'], ticket: ['id'], 'attachments/detail': ['id'], 'attachments/download': ['id'] }
function scoped(actor, row) {
  if (row.archived && actor.role === 'ROLE_UNIT') return false
  return actor.role === 'ROLE_ADMIN' || actor.role === 'ROLE_USER' && sameId(actor.id,row.userId) || actor.role === 'ROLE_UNIT' && sameId(actor.id,row.unitId)
}
const rowsFor = (state, type) => type === 'session' ? state.sessions : type === 'ticket' ? state.tickets : []
const canRead = (state, actor) => (type, id) => rowsFor(state,type).some(row => row.id === id && scoped(actor,row))
function requireRow(state, actor, type, id) {
  check(typeof id === 'string' && id.length >= 1 && id.length <= 64, '资源标识须为有效字符串')
  const row = rowsFor(state,type).find(row => row.id === id && scoped(actor,row))
  check(row, '资源不存在或当前账号无权访问', 'SUPPORT_FORBIDDEN')
  return row
}
function priority(value) {
  if (value === 'NORMAL') value = 'MEDIUM'
  if (value === 'IMPORTANT') value = 'HIGH'
  check(['LOW','MEDIUM','HIGH'].includes(value), '留言等级无效')
  return value
}
function shop(db, id) {
  numericId(id)
  const row = db.unit?.find(row => sameId(row.id,id))
  check(row, '店铺不存在', 'SUPPORT_NOT_FOUND')
  check(row.active !== false && row.enabled !== false && !['DISABLED','INACTIVE','BLOCKED'].includes(row.status), '店铺暂不接受新客服请求', 'SUPPORT_SHOP_UNAVAILABLE')
  return row
}
function order(db, actor, id, unitId) {
  if (id === undefined) return null
  numericId(id)
  const row = db.orders?.find(row => sameId(row.id,id) && sameId(row.userId,actor.id) && sameId(row.unitId,unitId))
  check(row, '关联订单必须属于当前顾客和所选店铺', 'SUPPORT_FORBIDDEN')
  return String(row.id)
}
function sender(actor) { return { senderId: actor.id, senderRole: actor.role, senderName: actor.name, senderLabel: actor.label, senderAvatar: actor.avatar } }
function history(state, actor, ticket, type, text, fromStatus, at, extra = {}) {
  ticket.history.push({ ...next(state), ticketId: ticket.id, ticketVersion: ticket.version, type, text, actorId: actor.id, actorRole: actor.role, actorName: actor.name, actorLabel: actor.label, fromStatus, toStatus: ticket.status, createdAt: at, ...extra })
}
function display(db, row) {
  const customer = db.user?.find(item => sameId(item.id,row.userId)), unit = db.unit?.find(item => sameId(item.id,row.unitId))
  const profile = db.support?.supportProfiles?.[`ROLE_USER:${row.userId}`]
  return { customerName: profile?.nickname || customer?.nickname || customer?.username || '已停用顾客', customerAvatar: profile?.avatarUrl || customer?.avatarUrl || '/avatar.svg', shopName: unit?.nickname || unit?.username || '已停用店铺', shopAvatar: unit?.avatarUrl || '/avatar.svg' }
}
function attachmentView(state, actor, row) {
  const attachments = (row.attachmentIds || []).map(id => metadata(readableAttachment(state,actor,id,canRead(state,actor))))
  // v1 inline images are read-only compatibility records, never new upload inputs.
  // Keep stored histories and bytes unchanged; reject URLs and arbitrary data MIME.
  const legacy = row.attachment
  if (legacy?.dataUrl && /^data:image\/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(legacy.dataUrl) && legacy.dataUrl.length <= 350000) {
    attachments.push({ name: typeof legacy.name === 'string' ? legacy.name : '旧版图片', mime: legacy.dataUrl.slice(5,legacy.dataUrl.indexOf(';')), size: legacy.size, dataUrl: legacy.dataUrl, legacyInline: true, readOnly: true, securityStatus: 'UNSCANNED_SIMULATION', environment: 'SIMULATION' })
  }
  return { attachments, attachment: attachments[0] || null }
}
function messageView(state, actor, message) {
  const { attachment, attachmentIds: ids, ...rest } = message
  return { ...clone(rest), senderId: String(message.senderId), ...attachmentView(state,actor,message) }
}
function sessionView(db, state, actor, session) {
  const messages = state.messages.filter(row => row.sessionId === session.id).sort((a,b) => a.sequence-b.sequence)
  const cursor = session.readCursors?.[actorKey(actor)] || 0
  const unread = messages.filter(row => row.sequence > cursor && !(row.senderRole === actor.role && sameId(row.senderId,actor.id))).length
  const { readCursors, ...rest } = session
  return { ...clone(rest), userId: String(session.userId), unitId: String(session.unitId), ...display(db,session), unread, unreadCount: unread, lastMessage: messages.length ? messageView(state,actor,messages.at(-1)) : null }
}
function availableStatuses(actor, ticket) {
  if (ticket.archived) return []
  const result = []
  if (actor.role === 'ROLE_USER') { if (ticket.status === 'RESOLVED') result.push('CLOSED') }
  else { const index = STATUSES.indexOf(ticket.status); if (index >= 0 && index < 3) result.push(STATUSES[index+1]) }
  if (['RESOLVED','CLOSED'].includes(ticket.status)) result.push('OPEN')
  return result
}
function ticketView(db, state, actor, ticket) {
  const { attachment, attachmentIds: ids, replies, ...rest } = ticket
  const ownerOrAdmin = actor.role === 'ROLE_ADMIN' || actor.role === 'ROLE_USER' && sameId(actor.id,ticket.userId)
  return { ...clone(rest), userId: String(ticket.userId), unitId: String(ticket.unitId), orderId: ticket.orderId == null ? null : String(ticket.orderId), ...display(db,ticket), ...attachmentView(state,actor,ticket), replies: (replies || []).map(row => messageView(state,actor,row)), replyCount: (replies || []).length, historyCount: (ticket.history || []).length, availableStatuses: availableStatuses(actor,ticket), canReply: !ticket.archived && ticket.status !== 'CLOSED', canArchive: ownerOrAdmin && !ticket.archived, canRestore: ownerOrAdmin && ticket.archived }
}
function pageNumber(value, fallback, max) {
  if (value === undefined) return fallback
  check((typeof value === 'number' || typeof value === 'string' && /^\d+$/.test(value)) && Number.isInteger(Number(value)) && Number(value) >= 1 && Number(value) <= max, '分页须为有效整数')
  return Number(value)
}
/** Read-only detached DTOs. beforeId returns the preceding newest 100 messages. */
export function supportQuery(db, actorInput, resource, params = {}) {
  const actor = identity(db,actorInput), state = stateOf(db)
  check(QUERY[resource], '未知客服查询', 'SUPPORT_NOT_FOUND')
  fields(params,QUERY[resource])
  if (resource === 'context') {
    const available = row => row.active !== false && row.enabled !== false && !['DISABLED','INACTIVE','BLOCKED'].includes(row.status)
    const shops = (db.unit || []).filter(row => actor.role === 'ROLE_ADMIN' || (actor.role === 'ROLE_UNIT' ? sameId(row.id,actor.id) : available(row))).map(row => ({ id: String(row.id), unitId: String(row.id), name: row.nickname || row.username, active: row.active !== false, acceptingRequests: available(row) }))
    return { actor: { id: actor.id, uid: actorKey(actor), name: actor.name, role: actor.role }, shops, orders: actor.role === 'ROLE_USER' ? (db.orders || []).filter(row => sameId(row.userId,actor.id)).map(row => ({ id: String(row.id), unitId: String(row.unitId), status: row.status, name: row.name || `本机模拟订单 ${row.id}`, totalMinor: Number.isSafeInteger(row.totalMinor) ? row.totalMinor : Number.isFinite(Number(row.price)) ? Math.round(Number(row.price)*100) : null })) : [], capabilities: { profileEditing: true, newCustomerRequest: actor.role === 'ROLE_USER', platformConfiguration: actor.role === 'ROLE_ADMIN', attachments: true, maxAttachmentCount: 5, maxAttachmentBytes: 524288, maxAttachmentsPerCommandBytes: 1048576, localAttachmentBudgetBytes: LOCAL_ATTACHMENT_BUDGET, localDomainBudgetBytes: LOCAL_DOMAIN_BUDGET, storage: 'LOCAL_BROWSER_DEMO', productionAuthentication: false, externalCallsEnabled: false } }
  }
  if (resource.startsWith('attachments/')) {
    const row = readableAttachment(state,actor,params.id,canRead(state,actor))
    return resource === 'attachments/download' ? download(row) : metadata(row)
  }
  if (resource === 'ticket') return ticketView(db,state,actor,requireRow(state,actor,'ticket',params.id))
  if (resource === 'session') {
    const session = requireRow(state,actor,'session',params.id)
    let before = Infinity
    if (params.beforeId !== undefined) {
      check(typeof params.beforeId === 'string', '消息游标须为字符串')
      const marker = state.messages.find(row => row.id === params.beforeId && row.sessionId === session.id)
      check(marker, '消息游标不属于当前会话')
      before = marker.sequence
    }
    const rows = state.messages.filter(row => row.sessionId === session.id && row.sequence < before).sort((a,b) => a.sequence-b.sequence)
    return { session: sessionView(db,state,actor,session), messages: rows.slice(-100).map(row => messageView(state,actor,row)), hasMore: rows.length > 100 }
  }
  const page = pageNumber(params.page,1,100000), limit = pageNumber(params.limit,20,100)
  check([10,20,50,100].includes(limit), '每页数量须为10、20、50或100')
  check(params.status === undefined || STATUSES.includes(params.status), '无效留言状态')
  const search = params.q === undefined ? undefined : textValue(params.q,100,'关键词').toLowerCase()
  if (params.unitId !== undefined) numericId(params.unitId)
  const matches = row => {
    if (params.unitId !== undefined && !sameId(row.unitId,params.unitId)) return false
    if (search === undefined) return true
    const labels = display(db,row)
    return `${row.title || ''}\n${row.content || ''}\n${labels.customerName}\n${labels.shopName}`.toLowerCase().includes(search)
  }
  const selectedPriority = params.priority === undefined ? undefined : priority(params.priority)
  check(params.includeArchived === undefined || [true,false,'true','false'].includes(params.includeArchived), '无效归档筛选')
  const includeArchived = params.includeArchived === true || params.includeArchived === 'true'
  const newest = (a,b) => b.updatedAt.localeCompare(a.updatedAt) || b.sequence-a.sequence
  const sessions = state.sessions.filter(row => scoped(actor,row) && matches(row)).map(row => sessionView(db,state,actor,row)).sort(newest)
  const readableTickets = state.tickets.filter(row => scoped(actor,row) && matches(row))
  const tickets = readableTickets.filter(row => (!row.archived || includeArchived) && (!params.status || row.status === params.status) && (!selectedPriority || row.priority === selectedPriority)).map(row => ticketView(db,state,actor,row)).sort(newest)
  const counts = { sessions: sessions.length, tickets: tickets.length, unreadMessages: sessions.reduce((sum,row) => sum+row.unread,0), unreadSessions: sessions.filter(row => row.unread > 0).length, openTickets: tickets.filter(row => row.status === 'OPEN').length, inProgressTickets: tickets.filter(row => row.status === 'IN_PROGRESS').length, resolvedTickets: tickets.filter(row => row.status === 'RESOLVED').length, closedTickets: tickets.filter(row => row.status === 'CLOSED').length, archivedTickets: readableTickets.filter(row => row.archived).length }
  const offset = (page-1)*limit
  return { sessions: sessions.slice(offset,offset+limit), tickets: tickets.slice(offset,offset+limit), counts, page, limit, sessionTotal: sessions.length, ticketTotal: tickets.length, truncated: sessions.length > limit || tickets.length > limit, hasMore: page*limit < sessions.length || page*limit < tickets.length }
}
/** Strict new writes; original inline attachments are read-only migration inputs. */
export function supportAction(db, actorInput, action, payload = {}, now = Date.now()) {
  const actor = identity(db,actorInput)
  check(ACTIONS[action], '未知客服操作；留言不能删除', 'SUPPORT_NOT_FOUND')
  fields(payload,[...ACTIONS[action],'clientKey'])
  const at = timestamp(now), key = clientKey(payload.clientKey), state = stateOf(db)
  const body = { ...payload }; delete body.clientKey
  const hash = fingerprint(body), previous = replay(state,actor,action,key,hash)
  if (previous) {
    if (action === 'attachments/upload') return metadata(readableAttachment(state,actor,previous.resultId,canRead(state,actor)))
    if (action === 'send-message') {
      const row = state.messages.find(row => row.id === previous.resultId)
      check(row, '已提交消息不存在', 'SUPPORT_SCHEMA')
      requireRow(state,actor,'session',row.sessionId)
      return messageView(state,actor,row)
    }
    if (['open-session','mark-read'].includes(action)) return sessionView(db,state,actor,requireRow(state,actor,'session',previous.resultId))
    return ticketView(db,state,actor,requireRow(state,actor,'ticket',previous.resultId))
  }
  let result
  if (action === 'attachments/upload') result = upload(state,actor,uploadMaterial(body),at)
  else if (action === 'open-session') {
    check(actor.role === 'ROLE_USER', '仅顾客可以发起店铺会话', 'SUPPORT_FORBIDDEN')
    const unit = shop(db,body.unitId)
    let session = state.sessions.find(row => sameId(row.userId,actor.id) && sameId(row.unitId,unit.id) && row.status === 'ACTIVE')
    if (!session) { session = { ...next(state), userId: actor.id, unitId: String(unit.id), status: 'ACTIVE', readCursors: {}, version: 1, createdAt: at, updatedAt: at }; state.sessions.push(session) }
    result = sessionView(db,state,actor,session)
  } else if (action === 'send-message') {
    const session = requireRow(state,actor,'session',body.sessionId), text = textValue(body.text,1000,'消息',true), ids = attachmentIds(body.attachmentIds)
    check(session.status === 'ACTIVE', '会话已关闭', 'SUPPORT_SESSION_CLOSED')
    check(text || ids.length, '请填写消息或添加附件')
    bind(state,actor,ids,'session',session.id,at,canRead(state,actor))
    const message = { ...next(state), sessionId: session.id, text, attachmentIds: ids, ...sender(actor), createdAt: at }
    state.messages.push(message); session.version++; session.updatedAt = at
    result = messageView(state,actor,message)
  } else if (action === 'mark-read') {
    const session = requireRow(state,actor,'session',body.sessionId)
    check(typeof body.lastSeenMessageId === 'string', '请提供实际显示的最后一条消息标识')
    const marker = state.messages.find(row => row.id === body.lastSeenMessageId && row.sessionId === session.id)
    check(marker, '已读游标不属于当前会话')
    session.readCursors ??= {}
    if (marker.sequence > (session.readCursors[actorKey(actor)] || 0)) { session.readCursors[actorKey(actor)] = marker.sequence; session.version++; session.updatedAt = at }
    result = sessionView(db,state,actor,session)
  } else if (action === 'create-ticket') {
    check(actor.role === 'ROLE_USER', '仅顾客可以创建留言', 'SUPPORT_FORBIDDEN')
    const unit = shop(db,body.unitId), title = textValue(body.title,200,'留言标题'), content = textValue(body.content,1500,'留言内容'), level = priority(body.priority), orderId = order(db,actor,body.orderId,unit.id), ids = attachmentIds(body.attachmentIds)
    const ticket = { ...next(state), userId: actor.id, unitId: String(unit.id), orderId, title, content, priority: level, attachmentIds: ids, status: 'OPEN', archived: false, version: 1, replies: [], history: [], createdAt: at, updatedAt: at }
    state.tickets.push(ticket)
    bind(state,actor,ids,'ticket',ticket.id,at,canRead(state,actor))
    history(state,actor,ticket,'CREATE','提交留言',null,at)
    result = ticketView(db,state,actor,ticket)
  } else {
    const ticket = requireRow(state,actor,'ticket',body.ticketId)
    version(ticket,body.version)
    const before = ticket.status
    if (action === 'archive-ticket' || action === 'restore-ticket') {
      check(actor.role === 'ROLE_ADMIN' || actor.role === 'ROLE_USER' && sameId(actor.id,ticket.userId), '仅留言本人或平台可归档和恢复', 'SUPPORT_FORBIDDEN')
      const archived = action === 'archive-ticket', reason = textValue(body.reason,500,'归档或恢复原因')
      check(ticket.archived !== archived, archived ? '留言已归档' : '留言未归档', 'INVALID_TRANSITION')
      Object.assign(ticket,{ archived, archivedAt: archived ? at : null, archivedBy: archived ? actor.id : null, version: ticket.version+1, updatedAt: at })
      history(state,actor,ticket,archived ? 'ARCHIVE' : 'RESTORE',reason,before,at)
    } else {
      check(!ticket.archived, '请先恢复已归档留言', 'SUPPORT_ARCHIVED')
      if (action === 'reply-ticket') {
        check(ticket.status !== 'CLOSED', '留言已关闭，请先重新打开', 'INVALID_TRANSITION')
        const text = textValue(body.text,1000,'回复'), ids = attachmentIds(body.attachmentIds)
        bind(state,actor,ids,'ticket',ticket.id,at,canRead(state,actor))
        const reply = { ...next(state), ticketId: ticket.id, text, attachmentIds: ids, ...sender(actor), createdAt: at }
        ticket.replies.push(reply); ticket.version++; ticket.updatedAt = at
        history(state,actor,ticket,'REPLY','添加回复',before,at,{ replyId: reply.id })
      } else {
        check(STATUSES.includes(body.status) && availableStatuses(actor,ticket).includes(body.status), '当前身份不允许此状态变更', 'SUPPORT_STATUS_TRANSITION')
        ticket.status = body.status; ticket.version++; ticket.updatedAt = at
        history(state,actor,ticket,'STATUS','更新留言状态',before,at)
      }
    }
    result = ticketView(db,state,actor,ticket)
  }
  remember(state,actor,action,key,hash,result.id)
  const detached = clone(result)
  commit(db,state)
  return detached
}
