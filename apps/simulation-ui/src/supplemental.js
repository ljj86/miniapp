import { ApiError, parseMinor, safePath } from './api.js';

export const applicationFields = Object.freeze(['merchantName', 'regionCode', 'contractVersion', 'materialSummary']);
export const editableApplicationStates = Object.freeze(['DRAFT', 'NEEDS_INFO']);
const materialLimits = { merchantName: 128, regionCode: 16, contractVersion: 40, materialSummary: 1000 };
function fail(message, code = 'INPUT_INVALID') { throw new ApiError(message, { code }); }
function textField(value, label, maximum) {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum) fail(`${label}需填写 1–${maximum} 个字符`);
  return value;
}
function versionOf(value) {
  const version = parseMinor(value);
  if (version > 100000000) fail('版本超出有效范围，请刷新记录');
  return version;
}
export function hasActiveGrant(grant, now = Date.now()) {
  if (grant?.status !== 'ACTIVE') return false;
  const valid = (date) => date == null || Number.isFinite(Date.parse(date));
  return valid(grant.validFrom) && valid(grant.validTo) &&
    (grant.validFrom == null || Date.parse(grant.validFrom) <= now) &&
    (grant.validTo == null || Date.parse(grant.validTo) > now);
}
export function hasResourceScope(grants, roles, resource = {}, now = Date.now()) {
  return (grants || []).some(grant => hasActiveGrant(grant, now) && roles.includes(grant.role) &&
    ['merchantUid', 'storeId', 'bookId'].every(key => grant[key] == null || grant[key] === resource[key]));
}
export function hasPlatformControllerScope(grants, now = Date.now()) {
  return hasResourceScope(grants, ['SIM_CONTROLLER'], {}, now);
}
export function applicationPermissions(workspace, application) {
  const user = workspace?.user;
  const active = user?.status === 'ACTIVE' && workspace?.roleCodes?.includes('USER');
  const visible = !!application && workspace?.resources?.applications?.some(row => row.id === application.id);
  const owned = active && visible && application.applicantId === user.id;
  return {
    create: !!active,
    edit: !!(owned && editableApplicationStates.includes(application.status)),
    submit: !!(owned && editableApplicationStates.includes(application.status)),
    review: !!(active && visible && application.status === 'SUBMITTED' && application.applicantId && application.applicantId !== user.id && workspace.roleCodes?.includes('MERCHANT_REVIEWER') && hasResourceScope(workspace.scopeGrants, ['MERCHANT_REVIEWER'], { merchantUid: application.merchantUid })),
  };
}
export function requirePermission(allowed) {
  if (!allowed) fail('当前身份、授权范围或记录状态不允许此操作，请刷新或切换身份', 'SCOPE_OR_STATE_DENIED');
}
export function buildApplicationCommand(kind, application, payload = {}, selectedFields = []) {
  if (kind === 'create') {
    const body = {};
    for (const field of applicationFields) if (payload[field] != null && payload[field] !== '') body[field] = textField(payload[field], field, materialLimits[field]);
    return { title: '保存模拟入驻草稿', path: '/api/v1/merchant-application-drafts', method: 'POST', body };
  }
  const id = application?.id;
  if (!id) fail('请先选择申请', 'INPUT_REQUIRED');
  const version = versionOf(application.version);
  if (kind === 'requestInformation') {
    if (application.status !== 'SUBMITTED') fail('只有待复核申请可要求补件', 'STATE_CONFLICT');
    return { title: '独立要求补充材料', path: safePath('/api/v1/admin/merchant-applications/{id}/request-information', { id }), method: 'POST', body: { version, reason: textField(payload.reason, '复核理由', 500) } };
  }
  if (!editableApplicationStates.includes(application.status)) fail('该申请当前不可修改或提交', 'STATE_CONFLICT');
  const path = safePath('/api/v1/merchant-application-drafts/{id}', { id });
  if (kind === 'submit') return { title: '提交已保存的材料版本', path: `${path}/submit`, method: 'POST', body: { version } };
  if (kind !== 'update') fail('未知申请操作');
  if (!selectedFields.length) fail('请明确选择本次修改的材料字段', 'INPUT_REQUIRED');
  const body = { version };
  for (const field of new Set(selectedFields)) {
    if (!applicationFields.includes(field)) fail('不允许修改所选字段');
    body[field] = textField(payload[field], field, materialLimits[field]);
  }
  return { title: '保存选中材料的新版本', path, method: 'PATCH', body };
}
export function notificationFailureBody(value, setting, reason, userId = '') {
  if (!/^\d+$/.test(String(value)) || !Number.isSafeInteger(Number(value)) || Number(value) > 100) fail('投递失败次数必须为 0–100 的整数');
  const body = { version: versionOf(setting?.version), remainingFailures: Number(value), reason: textField(reason, '故障测试原因', 500) };
  if (userId !== '') {
    if (!/^[0-9]{1,20}$/.test(String(userId))) fail('目标用户 ID 必须为服务端返回的数字字符串');
    body.userId = String(userId);
  }
  return body;
}
export function canRetryNotification(delivery) { return !!delivery && delivery.status === 'DEAD'; }
export function notificationRetryCommand(delivery, reason, authorized) {
  requirePermission(authorized);
  if (!canRetryNotification(delivery)) fail('只有死信通知可手动重试', 'STATE_CONFLICT');
  return { title: '重试模拟站内通知投递', path: safePath('/api/v1/notifications/{id}/retry', { id: delivery.id }), method: 'POST', body: { version: versionOf(delivery.version), reason: textField(reason, '重试原因', 500) } };
}
export function providerResultCommand(result, deliverCallback) {
  if (typeof deliverCallback !== 'boolean') fail('回调模式必须是布尔值');
  if (!result || result.environment !== 'SIMULATION') fail('仅允许模拟渠道事件');
  // JSON copy intentionally accepts Vue proxies while freezing the event at capture time.
  return { title: deliverCallback ? '投递模拟渠道结果' : '暂缓模拟渠道回调', path: '/api/v1/simulation-controls/provider-results', method: 'POST', body: { result: JSON.parse(JSON.stringify(result)), deliverCallback } };
}
export function repaymentComplete(repayment) { return repayment?.status === 'CONFIRMED'; }
