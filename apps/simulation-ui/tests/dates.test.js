import test from 'node:test';
import assert from 'node:assert/strict';
import { shanghaiDate, shanghaiTime, shanghaiDateTime } from '../src/dates.js';
test('business day and visible timestamps use Asia/Shanghai across 16:00 UTC', () => {
  assert.equal(shanghaiDate('2026-10-03T15:59:59Z'), '2026-10-03');
  assert.equal(shanghaiDate('2026-10-03T16:00:00Z'), '2026-10-04');
  assert.equal(shanghaiDate('2026-12-31T16:00:00Z'), '2027-01-01');
  assert.equal(shanghaiTime('2026-10-03T16:00:00Z'), '00:00:00');
  assert.equal(shanghaiDateTime('2026-10-03T16:00:00Z'), '2026-10-04 00:00:00');
  assert.equal(shanghaiDateTime(undefined), '未安排');
});
