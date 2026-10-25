import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { test } from 'node:test';
import { planClientIndexLoaderBarrier } from '../data/client-index-loader-barrier.mjs';
import { versionedFixture } from './lib/fixture.mjs';

const pristine = readFileSync(versionedFixture('dsh-host-frontend-static', 'lib', 'index.js'), 'utf8');

test('home HTML waits for host Loader and its previously registered client graph flush', async () => {
  const candidate = planClientIndexLoaderBarrier(pristine);
  assert.equal(planClientIndexLoaderBarrier(candidate), candidate);
  const scratch = mkdtempSync(join(tmpdir(), 'client-index-barrier-'));
  try {
    const deps = join(scratch, 'node_modules', '@deepseek-ai', 'schemastery');
    mkdirSync(deps, { recursive: true });
    writeFileSync(join(deps, 'package.json'), JSON.stringify({ name: '@deepseek-ai/schemastery', type: 'module', exports: './index.js' }));
    writeFileSync(join(deps, 'index.js'), 'const schema = { required() { return this; } }; export default { object: () => ({}), string: () => schema };');
    const modulePath = join(scratch, 'module.mjs');
    writeFileSync(modulePath, candidate);
    const { apply, serveStatic } = await import(pathToFileURL(modulePath));
    const dist = join(scratch, 'dist'); mkdirSync(dist);
    const index = join(dist, 'index.html'); writeFileSync(index, '<html><head></head><body></body></html>');
    const assets = join(dist, 'asset.js'); writeFileSync(assets, 'asset');
    let release;
    const pendingTasks = [new Promise((resolve) => { release = resolve; })];
    const loader = {
      getTasks() { return pendingTasks; },
      async await() {
        // Mirrors Cordis EntryTree.await() in dsh/vendor/loader/src/config/tree.ts:42-48.
        while (true) {
          const tasks = this.getTasks();
          if (!tasks.length) return;
          await Promise.allSettled(tasks);
        }
      },
    };
    const graph = [];
    const startupWait = loader.await();
    const flushDone = startupWait.then(() => { graph.push('initial'); graph.push('late-api-remotes'); });
    let handler;
    let renders = 0;
    const ctx = {
      loader,
      get(name) { return name === 'loader' ? this.loader : undefined; },
      webServer: {
        registerFallback(fn) { handler = fn; },
        renderIndex(html) { renders++; return html + graph.join(','); },
      },
      connection: { authorizeIndex() { return true; } },
      effect(fn) { fn(); },
    };
    apply(ctx, { distIndex: index });
    const response = () => ({ writeHead(status, headers) { this.status = status; this.headers = headers; }, end(body) { this.body = body; } });
    const first = response(); const concurrent = response();
    const firstDone = handler({ method: 'GET', url: '/' }, first);
    const concurrentDone = handler({ method: 'GET', url: '/index.html' }, concurrent);
    await Promise.resolve();
    assert.equal(renders, 0, 'first concurrent home requests stay behind the loader barrier');
    release();
    pendingTasks.splice(0);
    await Promise.all([firstDone, concurrentDone, flushDone]);
    assert.match(first.body, /initial,late-api-remotes$/);
    assert.match(concurrent.body, /initial,late-api-remotes$/);
    assert.equal(renders, 2);

    const assetResponse = response();
    let assetLoaderCalls = 0;
    ctx.loader.await = () => { assetLoaderCalls++; return new Promise(() => {}); };
    await handler({ method: 'GET', url: '/asset.js' }, assetResponse);
    assert.equal(assetResponse.body.toString(), 'asset');
    assert.equal(assetLoaderCalls, 0, 'non-home resources do not wait for Loader');

    ctx.loader.await = () => Promise.reject(new Error('host loader failed'));
    await assert.rejects(handler({ method: 'GET', url: '/' }, response()), /host loader failed/);
    ctx.loader.await = () => Promise.resolve();
    ctx.webServer.renderIndex = () => { throw new Error('client graph composition failed'); };
    await assert.rejects(handler({ method: 'GET', url: '/' }, response()), /client graph composition failed/);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});
