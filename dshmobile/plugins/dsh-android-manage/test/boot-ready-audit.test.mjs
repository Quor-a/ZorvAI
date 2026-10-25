// boot-ready-audit.test.mjs — 0.14.2-fx-2 / task-80：启动就绪审计的判据与 dispose 用例。
//
// 缺陷形态（用户报障）：boot 报成功，但 sessionController 缺席，
// session/selectModel 得到 `active Service "sessionController" is unavailable`。
// 真因：该条目不在上游 requiredStartupEntryIds 的 7 个 id 内，pending 时只 warn 不 throw。
//
// 本文件钉三条判据 + 一条 HMR 安全用例：
//   A. 缺位（多次重查后仍缺）⇒ 必须 WARN，且点名缺席服务与供给条目/pending 理由；
//   B. 正常（首次即全就绪）⇒ 0 条 WARN（默认静默）；
//   C. dispose 后 ⇒ 不得再有任何待触发回调（ctx.effect disposer 生效）。
//
// 反证方向：把 BOOT_AUDIT_ATTEMPTS 改成 1 且让首轮就绪（B 仍绿但 A 的「重查」语义丢）；
// 去掉 ctx.effect 的 disposer ⇒ C 立即红；去掉 console.warn ⇒ A 立即红。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'

const HERE = dirname(fileURLToPath(import.meta.url))
const mod = await import(pathToFileURL(join(HERE, '..', 'lib', 'index.js')).href)

/**
 * 最小 cordis 风格宿主桩：只实现审计用到的面。
 * @param services - 可用服务表（值非 undefined 即视为在场）。
 * @param entries - loader.entries() 返回的条目（**迭代器**，与真实 API 同形）。
 */
function makeCtx(services, entries = [], opts = {}) {
  const effects = []
  const listeners = new Set()
  let readyCommitted = false
  const home = opts.home ?? mkdtempSync(join(tmpdir(), 'dsh-boot-audit-'))
  const ctx = {
    tools: { register: () => {} },
    get(name) {
      if (name === 'appReady' && opts.withAppReady !== false) {
        return { onReady(l) { if (readyCommitted) { l(); return () => {} } listeners.add(l); return () => listeners.delete(l) } }
      }
      if (name === 'dshHomePath') return (...segs) => join(home, ...segs)
      if (name === 'loader') return { entries: () => entries[Symbol.iterator]() }
      if (name === 'appExit') return undefined
      return services[name]
    },
    effect(fn, _label) { const d = fn(); effects.push(d); return d },
    _commit() { readyCommitted = true; for (const l of [...listeners]) l(); listeners.clear() },
    _dispose() { for (const d of effects.reverse()) d?.() },
    _pending() { return listeners.size },
    _home: home,
  }
  return ctx
}

test('A. 缺位（多次重查后仍缺）⇒ WARN 且点名服务 + 供给条目与 pending 理由', async () => {
  const warns = []
  const orig = console.warn
  console.warn = (m) => warns.push(String(m))
  try {
    // settings 在场，另三个缺席；sessionController 的供给条目是 pending 状态且 inject 里要它
    const ctx = makeCtx({ settings: {} }, [
      { options: { id: 'session-controller', name: '@deepseek-ai/dsh-api-session-controller' }, fiber: { state: 0, inject: { sessionController: null } } },
      { options: { id: 'agent-default-model', name: '@deepseek-ai/dsh-agent-default-model' }, fiber: { state: 0, inject: { agentDefaultModel: null } } },
    ])
    // 直接调 apply 走注册路径（插件导出 apply）
    mod.apply(ctx, {})
    ctx._commit()
    await new Promise((r) => setTimeout(r, 1200))   // 覆盖 3 次 × 250ms
    assert.equal(warns.length, 1, '应恰好 1 条 WARN')
    const line = warns[0]
    assert.match(line, /boot-ready-audit/)
    assert.match(line, /sessionController/)
    assert.match(line, /waiting for service/)
    assert.match(line, /after 3 checks/)
  } finally { console.warn = orig }
})

test('B. 正常装配（首轮全就绪）⇒ 0 条 WARN', async () => {
  const warns = []
  const orig = console.warn
  console.warn = (m) => warns.push(String(m))
  try {
    const ctx = makeCtx({ sessionController: {}, agentDefaultModel: {}, typertGateway: {}, settings: {} })
    mod.apply(ctx, {})
    ctx._commit()
    await new Promise((r) => setTimeout(r, 900))
    assert.equal(warns.length, 0, '正常装配必须 0 条 WARN')
  } finally { console.warn = orig }
})

test('C. HMR 安全：dispose 后不得再触发审计', async () => {
  const warns = []
  const orig = console.warn
  console.warn = (m) => warns.push(String(m))
  try {
    const ctx = makeCtx({ settings: {} })
    mod.apply(ctx, {})
    assert.equal(ctx._pending(), 1, '注册后应有一个待触发监听（还未 commit）')
    ctx._dispose()   // 插件 dispose
    assert.equal(ctx._pending(), 0, 'dispose 后必须没有待触发回调（disposer 生效）')
    ctx._commit()    // 之后才 commit：不得再触发任何审计
    await new Promise((r) => setTimeout(r, 500))
    assert.equal(warns.length, 0, 'dispose 后 commit 不得再产生 WARN')
  } finally { console.warn = orig }
})

test('D. appReady 缺席（非启动器宿主）⇒ 跳过审计且不抛错', () => {
  const ctx = makeCtx({}, [], { withAppReady: false })
  assert.doesNotThrow(() => mod.apply(ctx, {}))
  assert.equal(ctx._pending(), 0)
})