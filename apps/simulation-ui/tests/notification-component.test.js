import test from 'node:test';
import assert from 'node:assert/strict';
import { parse, compileScript } from 'vue/compiler-sfc';
import { readFile, writeFile, unlink } from 'node:fs/promises';
// Exercise the compiled production setup handlers without a browser or a DOM driver.
const input = new URL('../src/components/NotificationControls.vue', import.meta.url);
const compiledPath = new URL(`../src/components/.notification-test-${process.pid}.mjs`, import.meta.url);
const compiled = compileScript(parse(await readFile(input, 'utf8')).descriptor, { id: 'notification-component-test' }).content;
await writeFile(compiledPath, compiled);
let Component;
try { Component = (await import(compiledPath.href)).default; } finally { await unlink(compiledPath); }
function setup(overrides = {}) {
  const events = [];
  const props = { authorized: true, busy: false, setting: { version: 8, remainingFailures: 0 }, deliveries: [{ id: '303', version: 4, status: 'DEAD', attemptCount: 3 }], ...overrides };
  const bindings = Component.setup(props, { expose() {}, emit: (...event) => events.push(event) });
  return { props, bindings, events };
}
test('notification configuration handler rejects fractional values without emitting', () => {
  const { bindings, events } = setup();
  bindings.failures.value = '2.5';
  bindings.configure();
  assert.equal(events.length, 0);
  assert.match(bindings.error.value, /整数/);
});
test('notification handlers enforce negative authorization and busy state beyond disabled buttons', () => {
  for (const override of [{ authorized: false }, { busy: true }]) {
    const { bindings, events } = setup(override);
    bindings.selectedId.value = '303';
    bindings.configure();
    bindings.retry();
    assert.equal(events.length, 0);
    assert.match(bindings.error.value, /不允许/);
  }
});
test('notification component emits current server versions and safe retry command', () => {
  const { bindings, events } = setup();
  bindings.failures.value = '3';
  bindings.configure();
  bindings.selectedId.value = '303';
  bindings.retry();
  assert.equal(events.length, 2);
  assert.equal(events[0][1].body.version, 8);
  assert.equal(events[0][1].body.remainingFailures, 3);
  assert.equal(events[1][1].path, '/api/v1/notifications/303/retry');
  assert.equal(events[1][1].body.version, 4);
});
test('automatic RETRY_WAIT delivery cannot emit a manual retry and history is read-only', () => {
  const { bindings, events } = setup({ deliveries: [{ id: '303', version: 5, status: 'RETRY_WAIT' }] });
  bindings.selectedId.value = '303';
  bindings.retry();
  assert.equal(events.length, 0);
  bindings.inspect('attempts');
  bindings.inspect('retries');
  assert.deepEqual(events.map(([,command]) => [command.method, command.path]), [['GET', '/api/v1/notification-outbox/303/attempts'], ['GET', '/api/v1/notification-outbox/303/retries']]);
});
