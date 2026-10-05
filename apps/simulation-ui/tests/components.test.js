import test from 'node:test';
import assert from 'node:assert/strict';
import { createSSRApp } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { createServer } from 'vite';
import { readFile } from 'node:fs/promises';
const source = JSON.parse(await readFile(new URL('../src/contract.json', import.meta.url), 'utf8'));
test('Vue components render server data boundaries and the preserved 76-operation explorer', async t => {
  const server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' });
  try {
    await t.test('initial workflow does not invent balances or emit session credentials', async () => {
      const { default: App } = await server.ssrLoadModule('/src/App.vue');
      const html = await renderToString(createSSRApp(App));
      assert.match(html, /未读取/);
      assert.match(html, /模拟/);
      assert.doesNotMatch(html, /¥0\.00|Bearer |accessToken|refreshToken/);
    });
    await t.test('unauthorized notification controls cannot submit or retry', async () => {
      const { default: Controls } = await server.ssrLoadModule('/src/components/NotificationControls.vue');
      const html = await renderToString(createSSRApp(Controls, { authorized: false, setting: { version: 4, remainingFailures: 2 }, deliveries: [{ id: '30', status: 'DEAD', attemptCount: 6 }] }));
      assert.match(html, /没有已读取的平台级控制范围/);
      const buttons = [...html.matchAll(/<button\b([^>]*)>/g)];
      assert.equal(buttons.length, 4);
      assert.ok(buttons.every(([,attrs]) => attrs.includes('disabled')));
      assert.match(html, /已尝试 6 次/);
    });
    await t.test('controller cannot configure failures before current version is read', async () => {
      const { default: Controls } = await server.ssrLoadModule('/src/components/NotificationControls.vue');
      const html = await renderToString(createSSRApp(Controls, { authorized: true }));
      assert.match(html, /尚未读取故障配置版本/);
      assert.ok([...html.matchAll(/<button\b([^>]*)>/g)].every(([,attrs]) => attrs.includes('disabled')));
    });
    await t.test('all original operation identities map to preserved methods, paths and schemas', async () => {
      assert.equal(source.operations.length, 76);
      assert.equal(new Set(source.operations.map(op => op.id)).size, 76);
      for (const op of source.operations) {
        assert.ok(source.paths[op.path.replace('/api/v1', '')]?.[op.method.toLowerCase()], op.id);
        if (op.request) assert.ok(source.schemas[op.request], op.request);
      }
      const { default: Explorer } = await server.ssrLoadModule('/src/components/ContractExplorer.vue');
      const html = await renderToString(createSSRApp(Explorer, { context: {} }));
      assert.match(html, /契约操作/);
      assert.match(html, /getCurrentRule|当前.*规则/);
    });
  } finally { await server.close(); }
});
