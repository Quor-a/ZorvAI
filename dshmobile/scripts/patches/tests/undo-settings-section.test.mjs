// undo-settings-section.test.mjs — E9 摘除设置页「快照」分区回归。
//
// 2026-10-26 用户拍板「快照和通知还在」：harness 设置导航中「快照」「通知」两区删除
// （「手机控制」「开发者选项」已随上一包删除；通知侧 #429 迁 ZorvAI 设置，壳侧
// HarnessNotifyScreen 直调 NotifyCenter）。「快照」= dsh-undo 插件的
// settings.section 一级分区（label = t("settings.nav")，i18n 文案「快照」）。
//
// 本测试不只核对字节：把 vendor 插件拷进隔离包、施加同一张 E9 补丁后，用 mock ctx
// 真跑 apply()——断言 settings.section / settings.undo.item 两个槽零注册，
// 而会话头部 actions 槽（undo/redo 按钮 + 快照徽章，E8 小绿点）照常注册。
import { existsSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const targetRel = 'dsh-undo-savepoint/lib/client.js'
const source = join(repoRoot, 'vendor', targetRel)
const patchRunner = join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs')
const failures = []

function check(label, ok, detail) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

const scratch = mkdtempSync(join(tmpdir(), 'undo-settings-section-'))
try {
  const target = join(scratch, targetRel)
  mkdirSync(dirname(target), { recursive: true })
  // 整目录拷 lib/ 并归一为 LF（client.js 虽无跨文件 import，与 undo-route-auth 保持同模式；
  // E9 锚点对 CRLF/LF 都命中，归一后结构断言按 LF 写死）。
  const libDir = join(repoRoot, 'vendor', 'dsh-undo-savepoint', 'lib')
  for (const name of readdirSync(libDir, { recursive: true })) {
    const srcPath = join(libDir, name)
    if (!statSync(srcPath).isFile()) continue
    const destPath = join(scratch, 'dsh-undo-savepoint', 'lib', name)
    mkdirSync(dirname(destPath), { recursive: true })
    if (/\.(mjs|js|json)$/.test(name)) {
      writeFileSync(destPath, readFileSync(srcPath, 'utf8').replace(/\r\n/g, '\n'))
    } else {
      writeFileSync(destPath, readFileSync(srcPath))
    }
  }

  const applied = spawnSync(process.execPath,
    [patchRunner, scratch, '--apply', '--scope', 'vendor', '--only', 'undo-E9'],
    { encoding: 'utf8' })
  check('apply-patches exits 0', applied.status === 0,
    (applied.stderr || applied.stdout || '').trim().split('\n').slice(-3).join(' '))
  const patched = readFileSync(target, 'utf8')

  // ① 分区注册整块摘除（marker + 两槽 + 门控变量一个不留）
  check('E9 marker removed', !patched.includes('// Snapshot settings: own sidebar section'))
  check('settings.section 一级分区注册摘除', !patched.includes('slots:settings.section'))
  check('settings.undo.item 页面槽注册摘除', !patched.includes('slots:settings.undo.item'))
  check('sectionOk 门控变量摘除', !patched.includes('sectionOk'))
  check('dsh-undo 分区 id 摘除', !patched.includes('id: "dsh-undo"'))
  check('分区 label 锚点 t("settings.nav") 摘除', !patched.includes('label: () => t("settings.nav")'))

  // ② 保留面：头部 actions 槽（undo/redo 按钮 + 快照徽章）与快照面板、急救 CLI、既有补丁
  check('会话头部 actions 槽保留', patched.includes('slots:conversation.session.header.actions'))
  check('快照面板 SnapshotPanel 保留', patched.includes('exports.SnapshotPanel = SnapshotPanel'))
  check('E8 小绿点徽章保留', patched.includes('dsh-mobile dot-only badge'))
  check('U1 路由鉴权保留（index.js 不受影响）',
    readFileSync(join(scratch, 'dsh-undo-savepoint', 'lib', 'index.js'), 'utf8')
      .includes('dsh-mobile undo route auth (U1)'))
  // 死代码保留：无注册即无渲染入口，删除会牵动更多锚点
  check('UndoSettingsSection 定义与导出留为死代码', patched.includes('exports.UndoSettingsSection = UndoSettingsSection'))
  check('UndoMainPage 定义与导出留为死代码', patched.includes('exports.UndoMainPage = UndoMainPage'))

  // ③ 语法与幂等
  const syntax = spawnSync(process.execPath, ['--check', target], { encoding: 'utf8' })
  check('patched artifact parses', syntax.status === 0, (syntax.stderr || '').split('\n')[0])
  const second = spawnSync(process.execPath,
    [patchRunner, scratch, '--apply', '--scope', 'vendor', '--only', 'undo-E9'],
    { encoding: 'utf8' })
  check('re-apply is idempotent', second.status === 0 && readFileSync(target, 'utf8') === patched)

  // ④ 行为回归：mock ctx 真跑 apply()——设置两槽零注册，头部 actions 槽照常注册
  const captured = {}
  const requireStub = (id) => {
    if (id === 'react') {
      return { useState: () => [null, () => {}], useEffect: () => {}, useCallback: (fn) => fn, Fragment: 'Fragment' }
    }
    if (id === 'react/jsx-runtime') return { jsx: () => null, jsxs: () => null, Fragment: 'Fragment' }
    throw new Error('unexpected require: ' + id)
  }
  const windowStub = { __ModuleLoader__: { load: (def) => { captured.def = def } } }
  new Function('window', 'require', patched)(windowStub, requireStub)
  const mod = captured.def.factory(requireStub)
  const injections = []
  const registrations = []
  const ctx = {
    effect(fn) {
      try { const v = fn(); if (typeof v === 'function') v() } catch { /* 兼容盖子本就降级 */ }
      return () => {}
    },
    locale: {
      register() {},
      bind() { return (key) => key },
    },
    slots: {
      inject(name, factory) {
        injections.push(name)
        return factory()
      },
      register(descriptor) {
        registrations.push(descriptor)
        return descriptor
      },
    },
  }
  mod.apply(ctx)
  check('apply() 注册会话头部 actions 槽（保留面）',
    injections.includes('conversation.session.header.actions'), JSON.stringify(injections))
  check('apply() 不再注册 settings.section 分区',
    !injections.includes('settings.section') &&
    !registrations.some((d) => d && d.name === 'settings.section'),
    JSON.stringify(registrations.map((d) => d && d.name)))
  check('apply() 不再注册 settings.undo.item 页面槽',
    !injections.includes('settings.undo.item') &&
    !registrations.some((d) => d && d.name === 'settings.undo.item'),
    JSON.stringify(registrations.map((d) => d && d.name)))
  check('apply() 不再产出 id=dsh-undo 的分区描述子',
    !registrations.some((d) => d && d.id === 'dsh-undo'))

  // ⑤ 仓库真树状态：vendor 副本必须已施加 E9（构建门禁口径）
  const repoClient = readFileSync(source, 'utf8')
  check('repo vendor client.js 已施加 E9', !repoClient.includes('// Snapshot settings: own sidebar section'))
} finally {
  rmSync(scratch, { recursive: true, force: true })
}

if (failures.length > 0) {
  console.error('\nundo-settings-section: ' + failures.length + ' check(s) failed: ' + failures.join('; '))
  process.exit(1)
}
console.log('\nundo-settings-section: all checks passed')
