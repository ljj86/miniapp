import test from 'node:test';
import assert from 'node:assert/strict';
import { reactive } from 'vue';
import { buildApplicationCommand, applicationPermissions, hasResourceScope, hasPlatformControllerScope, notificationFailureBody, notificationRetryCommand, canRetryNotification, providerResultCommand, repaymentComplete } from '../src/supplemental.js';
const payload = { merchantName: '合成商家', regionCode: '310101', contractVersion: 'SIM-V1', materialSummary: '模拟材料', reason: '请补充测试材料' };
const draft = { id: '9223372036854775807', applicantId: '101', merchantUid: 's100', status: 'DRAFT', version: 3, materialVersion: 1 };
const grant = { role: 'SIM_CONTROLLER', status: 'ACTIVE' };
const workspace = (extra = {}) => ({ user: { id: '101', uid: 'y101', status: 'ACTIVE' }, roleCodes: ['USER'], resources: { applications: [draft] }, scopeGrants: [], ...extra });
test('draft creation allows partial materials and never forwards metadata or review reasons', () => {
  const command = buildApplicationCommand('create', null, { merchantName: payload.merchantName, materialSummary: '', applicantId: '999', reason: 'do not send' });
  assert.equal(command.path, '/api/v1/merchant-application-drafts');
  assert.deepEqual(command.body, { merchantName: payload.merchantName });
  assert.deepEqual(buildApplicationCommand('create', null, {}).body, {});
});
test('PATCH sends only explicitly selected material fields with current server version', () => {
  const command = buildApplicationCommand('update', draft, payload, ['materialSummary']);
  assert.equal(command.path, '/api/v1/merchant-application-drafts/9223372036854775807');
  assert.equal(command.method, 'PATCH');
  assert.deepEqual(command.body, { version: 3, materialSummary: '模拟材料' });
  assert.deepEqual(buildApplicationCommand('update', draft, payload, ['regionCode', 'regionCode']).body, { version: 3, regionCode: '310101' });
});
test('draft PATCH rejects no selection, unknown fields, and empty or oversized selected values', () => {
  for (const fields of [[], ['version'], ['applicantId'], ['materialSummary', 'status']]) assert.throws(() => buildApplicationCommand('update', draft, payload, fields));
  for (const value of ['', '   ', 'a'.repeat(1001)]) assert.throws(() => buildApplicationCommand('update', draft, { materialSummary: value }, ['materialSummary']));
});
test('submitting saved materials sends version only for DRAFT or NEEDS_INFO', () => {
  for (const status of ['DRAFT', 'NEEDS_INFO']) {
    const command = buildApplicationCommand('submit', { ...draft, status, version: 7 }, payload, ['merchantName']);
    assert.equal(command.path, '/api/v1/merchant-application-drafts/9223372036854775807/submit');
    assert.deepEqual(command.body, { version: 7 });
  }
});
test('submitted, approved, and rejected applications reject update and resubmit', () => {
  for (const status of ['SUBMITTED', 'APPROVED', 'REJECTED']) for (const kind of ['update', 'submit']) assert.throws(() => buildApplicationCommand(kind, { ...draft, status }, payload, ['materialSummary']), e => e.code === 'STATE_CONFLICT');
});
test('needs-info request is versioned, reason-bound, and limited to SUBMITTED', () => {
  const command = buildApplicationCommand('requestInformation', { ...draft, status: 'SUBMITTED' }, payload);
  assert.equal(command.path, '/api/v1/admin/merchant-applications/9223372036854775807/request-information');
  assert.deepEqual(command.body, { version: 3, reason: payload.reason });
  assert.throws(() => buildApplicationCommand('requestInformation', draft, payload));
  assert.throws(() => buildApplicationCommand('requestInformation', { ...draft, status: 'SUBMITTED' }, { reason: ' ' }));
});
test('applicant edit controls require active authenticated ownership and visible row', () => {
  assert.equal(applicationPermissions(workspace(), draft).edit, true);
  for (const w of [null, workspace({ user: { id: '202', status: 'ACTIVE' } }), workspace({ user: { id: '101', status: 'DISABLED' } }), workspace({ roleCodes: [] }), workspace({ resources: { applications: [] } })]) assert.equal(applicationPermissions(w, draft).edit, false);
  assert.equal(applicationPermissions(workspace(), { ...draft, status: 'REJECTED' }).submit, false);
});
test('review controls deny self review, fixture role hints, and mismatched merchant scopes', () => {
  const application = { ...draft, status: 'SUBMITTED' };
  const reviewer = workspace({ user: { id: '202', status: 'ACTIVE' }, roleCodes: ['USER', 'MERCHANT_REVIEWER'], scopeGrants: [{ role: 'MERCHANT_REVIEWER', status: 'ACTIVE', merchantUid: 's100' }] });
  assert.equal(applicationPermissions(reviewer, application).review, true);
  assert.equal(applicationPermissions({ ...reviewer, user: { id: '101', status: 'ACTIVE' } }, application).review, false);
  assert.equal(applicationPermissions({ ...reviewer, scopeGrants: [{ role: 'MERCHANT_REVIEWER', status: 'ACTIVE', merchantUid: 'other' }] }, application).review, false);
  assert.equal(applicationPermissions(workspace({ fixtureRoles: ['MERCHANT_REVIEWER'] }), application).review, false);
});
test('platform controller requires an active unrestricted grant, not a role label', () => {
  assert.equal(hasPlatformControllerScope([grant]), true);
  for (const g of [{ ...grant, merchantUid: 's100' }, { ...grant, storeId: '8' }, { ...grant, bookId: '5' }, { ...grant, status: 'REVOKED' }, { ...grant, validFrom: '2999-01-01T00:00:00Z' }, { ...grant, validTo: '2000-01-01T00:00:00Z' }, { ...grant, validTo: 'invalid' }, { role: 'SIM_CONTROLLER' }]) assert.equal(hasPlatformControllerScope([g]), false);
  assert.equal(hasPlatformControllerScope(), false);
});
test('merchant scope checks distinguish another store and unknown selected resources', () => {
  const g = [{ role: 'OWNER', status: 'ACTIVE', merchantUid: 's100', storeId: '20' }];
  assert.equal(hasResourceScope(g, ['OWNER'], { merchantUid: 's100', storeId: '20' }), true);
  assert.equal(hasResourceScope(g, ['OWNER'], { merchantUid: 's100', storeId: '21' }), false);
  assert.equal(hasResourceScope(g, ['OWNER'], { merchantUid: 's100' }), false);
  assert.equal(hasResourceScope(g, ['CLERK'], { merchantUid: 's100', storeId: '20' }), false);
});
test('notification failure setting uses server version, bounded integer count, and reason', () => {
  assert.deepEqual(notificationFailureBody('0', { version: 4 }, '恢复通知'), { version: 4, remainingFailures: 0, reason: '恢复通知' });
  assert.equal(notificationFailureBody('100', { version: 4 }, '故障测试', '9223372036854775807').userId, '9223372036854775807');
  for (const value of ['-1', '1.5', '1e2', '101', '', '9007199254740993']) assert.throws(() => notificationFailureBody(value, { version: 4 }, '测试'));
  assert.throws(() => notificationFailureBody('3', null, '测试'));
  assert.throws(() => notificationFailureBody('3', { version: 4 }, ''));
});
test('only DEAD deliveries can start a versioned, authorized retry cycle', () => {
  const delivery = { id: '400', version: 9, status: 'DEAD', attemptCount: 6 };
  assert.deepEqual(notificationRetryCommand(delivery, '核查后重试', true), { title: '重试模拟站内通知投递', path: '/api/v1/notifications/400/retry', method: 'POST', body: { version: 9, reason: '核查后重试' } });
  assert.throws(() => notificationRetryCommand(delivery, '重试', false), e => e.code === 'SCOPE_OR_STATE_DENIED');
  for (const status of ['PENDING', 'RETRY_WAIT', 'DELIVERED', 'RETRY']) { assert.equal(canRetryNotification({ status }), false); assert.throws(() => notificationRetryCommand({ ...delivery, status }, '重试', true)); }
});
test('held callback is copied from Vue reactive data and preserves event identity', () => {
  const result = reactive({ providerEventId: 'evt-100', environment: 'SIMULATION', amountMinor: 2000, reference: 'repay-20' });
  const held = providerResultCommand(result, false);
  result.amountMinor = 999;
  const delivered = providerResultCommand(held.body.result, true);
  assert.deepEqual(held.body.result, delivered.body.result);
  assert.equal(held.body.result.amountMinor, 2000);
  assert.equal(held.body.deliverCallback, false);
  assert.equal(delivered.body.deliverCallback, true);
  assert.throws(() => providerResultCommand({ environment: 'PRODUCTION' }, false));
});
test('repayment completion is based on backend CONFIRMED status', () => {
  assert.equal(repaymentComplete({ status: 'CONFIRMED' }), true);
  for (const status of ['SUCCEEDED', 'PENDING', 'FAILED', 'TIMEOUT']) assert.equal(repaymentComplete({ status }), false);
});
