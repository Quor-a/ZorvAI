// boot-third-party-isolation-g3.test.mjs — G3 补丁回归：第三方插件 boot 期失败隔离（0.14.1）。
//
// 真因（真实用户反馈 报错反馈/0.14.0/20260919-125714-engine-died-during-boot）：用户自装的
// dsh-live2d-pets 在 import 期抛 SyntaxError（上游 @deepseek-ai/dsh-settings 不再导出
// settingsNamespace）→ 整树 boot 失败、engine exit=1。
// 条目断言层（assertEntriesActivated）结构上覆盖不到：import 失败在更早的链路就冒泡——
// boot() → mountRootInclude() → loader.create() → EntryTree.update（cordis-plugin-loader）
// → updateError('import') 抛出，assertEntriesActivated 根本不会被执行。故容错只能做在挂载点。
//
// 本测试：
//   ① 补丁幂等 / marker / node --check / 反 no-op（boot() 不再直接挂载 root include）；
//   ② 用抽取出的隔离器 + 桩 mountRootInclude 驱动全部判定分支：
//      第三方失败→隔离重试并点名；官方包与出厂移动侧包失败→仍响亮失败；无法识别→仍失败；
//      超过上限→仍失败并给完整清单；disabled patch 形状正确；被跳过清单挂到 globalThis；
//   ③ boot 期容错只有一个真源：产物里除 G3 之外不得再有别的容错标记（0.14.2 撤销 G1 后的口径）。
//
// 用法：node scripts/patches/tests/boot-third-party-isolation-g3.test.mjs
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const TARGET = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js'
const FIXTURE = versionedFixture('dsh-app-boot', 'lib', 'index.js')

const failures = []
function check(label, ok, detail) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}
function extractFunction(source, signature) {
  const start = source.indexOf(signature)
  if (start < 0) throw new Error('function not found: ' + signature)
  let depth = 0
  for (let i = source.indexOf('{', start); i < source.length; i += 1) {
    if (source[i] === '{') depth += 1
    else if (source[i] === '}') {
      depth -= 1
      if (depth === 0) return source.slice(start, i + 1)
    }
  }
  throw new Error('unbalanced braces for ' + signature)
}
/** 抽取 G3 的整块（常量 + 三个函数），供桩驱动。 */
function extractG3Block(source) {
  const start = source.indexOf('/* dsh-mobile third-party boot isolation (G3)')
  if (start < 0) throw new Error('G3 block not found')
  const anchor = source.indexOf('async function boot(binName, absoluteConfigPath, patches, prepare, bareModuleBaseUrl) {')
  if (anchor < 0) throw new Error('boot() anchor not found')
  return source.slice(start, anchor)
}
/** 用桩 mountRootInclude 造一个隔离器实例。 */
function buildIsolator(source, mountStub) {
  const src = extractG3Block(source)
  const factory = new Function('mountRootInclude', 'console',
    src + '\nreturn { dshMobileMountRootIncludeTolerant, dshMobileCollectEntryFailures, dshMobileIsShippedPlugin, dshMobileIsIsolatableEntry, DSH_MOBILE_BOOT_SKIPPED_PLUGINS, DSH_MOBILE_BOOT_SKIP_LIMIT };')
  const warnings = []
  const api = factory(mountStub, { warn: (line) => warnings.push(String(line)), log: () => {}, error: () => {} })
  return { ...api, warnings }
}
/** 造一个与 loader updateError 同形态的失败错误（含 cause 链与 AggregateError 折叠）。 */
function loaderImportError(id, name) {
  const syntax = new SyntaxError(`The requested module '@deepseek-ai/dsh-settings' does not provide an export named 'settingsNamespace'`)
  const inner = new Error(`failed to import loader entry ${id} (${name}): ${syntax.message}`, { cause: syntax })
  return new Error(`failed to apply loader entry include (cordis:include): ${inner.message}`, { cause: inner })
}
const importErrorFor = (...pairs) => {
  const errors = pairs.map(([id, name]) => loaderImportError(id, name))
  if (errors.length === 1) return errors[0]
  return new AggregateError(errors, 'loader entries failed to apply')
}

const scratch = mkdtempSync(join(tmpdir(), 'g3-test-'))
try {
  const target = join(scratch, TARGET)
  mkdirSync(dirname(target), { recursive: true })
  // 夹具是**未打补丁**的真产物字节（随版，见 tests/lib/fixture.mjs）——G3 的判据必须建立在
  // 「上游原样」之上，拿打过补丁的树测补丁等于什么也没测。
  writeFileSync(target, readFileSync(FIXTURE, 'utf8').replace(/\r\n/g, '\n'))
  const beforePatch = readFileSync(target, 'utf8')
  check('前置：fixture 是未施加任何本方补丁的真产物', !beforePatch.includes('dsh-mobile'))

  const apply = () => spawnSync(process.execPath,
    [join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs'), scratch, '--apply', '--scope', 'engine', '--only', 'boot-third-party-isolation-G3'],
    { encoding: 'utf8' })
  const applied = apply()
  check('apply-patches exits 0（仅 G3）', applied.status === 0,
    (applied.stdout || applied.stderr || '').trim().split('\n').slice(-2).join(' | '))
  const patched = readFileSync(target, 'utf8')
  check('G3 marker 在场', patched.includes('dsh-mobile third-party boot isolation (G3)'))
  check('隔离器与收集器在场',
    patched.includes('dshMobileMountRootIncludeTolerant') && patched.includes('dshMobileCollectEntryFailures'))
  check('反 no-op：boot() 不再直接挂载 root include',
    !patched.includes('\t\tawait mountRootInclude(ctx, absoluteConfigPath, patches, bareModuleBaseUrl, binName);')
    && patched.includes('await dshMobileMountRootIncludeTolerant(ctx, binName, absoluteConfigPath, patches, bareModuleBaseUrl);'))
  check('boot 期容错唯一真源（无第二种容错标记混在产物里）',
    (patched.match(/dsh-mobile third-party boot isolation \(G3\)/g) || []).length >= 1
    && !patched.includes('dsh-mobile boot tolerance'))
  const parse = spawnSync(process.execPath, ['--check', target], { encoding: 'utf8' })
  check('patched file parses', parse.status === 0, (parse.stderr || '').split('\n')[0])
  apply()
  check('re-apply is idempotent', readFileSync(target, 'utf8') === patched)

  // ── ② 决策逻辑：桩 mountRootInclude 记录收到哪些 disabled patch ──
  const mk = (failuresSpec, opts = {}) => {
    const calls = []
    let attempt = 0
    const mountStub = async (ctx, configPath, patches) => {
      calls.push({ patches: patches.map((p) => ({ ...p })) })
      attempt += 1
      // 第一次调用：按失败清单抛；后续调用（隔离重试）：默认成功。
      if (attempt === 1 && failuresSpec.length > 0) throw importErrorFor(...failuresSpec.map((f) => [f.id, f.name]))
      if (opts.throwEveryTime) throw importErrorFor(...failuresSpec.map((f) => [f.id, f.name]))
      return undefined
    }
    const iso = buildIsolator(patched, mountStub)
    return { iso, calls, run: () => iso.dshMobileMountRootIncludeTolerant({}, 'dsh', '/cfg/cordis.yml', [], undefined) }
  }

  // ① 第三方失败 → 隔离重试成功 + 点名告警
  {
    const { iso, calls, run } = mk([{ id: 'live2d-pet', name: 'dsh-live2d-pets' }])
    await run()
    check('② 第三方插件 import 失败：boot 不抛（引擎能起来）', true)
    check('② 重试时携带 disabled patch', calls.length === 2 && calls[1].patches.length === 1,
      'calls=' + calls.length + ' retryPatches=' + JSON.stringify(calls[1] && calls[1].patches))
    check('② disabled patch 形状正确（{id,name,disabled:true}）',
      calls[1] && calls[1].patches[0].id === 'live2d-pet' && calls[1].patches[0].name === 'dsh-live2d-pets'
      && calls[1].patches[0].disabled === true,
      JSON.stringify(calls[1] && calls[1].patches[0]))
    check('② 告警点名坏插件（可诊断）',
      iso.warnings.some((w) => w.includes('dsh-live2d-pets') && w.includes('skipped')),
      iso.warnings.join(' | ').slice(0, 200))
    check('② 被跳过清单挂到 globalThis（壳侧/诊断可读）',
      Array.isArray(globalThis.__dshMobileBootSkippedPlugins)
      && globalThis.__dshMobileBootSkippedPlugins.includes('dsh-live2d-pets'),
      JSON.stringify(globalThis.__dshMobileBootSkippedPlugins))
  }

  // ② 官方包失败 → 仍响亮失败（核心坏掉必须可见）
  {
    const { run } = mk([{ id: 'core-thing', name: '@deepseek-ai/dsh-settings' }])
    let threw = null
    try { await run() } catch (error) { threw = error }
    check('② 官方包（@deepseek-ai/*）失败：必须仍抛出（反向断言，不得被当成功）',
      threw !== null && /does not provide an export named/.test(threw.message), threw === null ? '未抛出' : threw.message.slice(0, 90))
  }
  // ③ 出厂移动侧包失败 → 仍响亮失败
  {
    const { run } = mk([{ id: 'android-bridge', name: '@dsh-android/dsh-android-bridge' }])
    let threw = null
    try { await run() } catch (error) { threw = error }
    check('② 出厂移动侧包（@dsh-android/*）失败：必须仍抛出', threw !== null, threw === null ? '未抛出' : '')
  }
  // ④ 出货具名第三方（dshmarketplace-plugin 等）失败 → 仍响亮失败
  {
    const { run } = mk([{ id: 'dshmarketplace', name: 'dshmarketplace-plugin' }])
    let threw = null
    try { await run() } catch (error) { threw = error }
    check('② 出货具名插件（dshmarketplace-plugin）失败：必须仍抛出', threw !== null, threw === null ? '未抛出' : '')
  }
  // ⑤ 无法识别的失败（没有 loader entry 形态）→ 原样抛出，不得误吞
  {
    const mountStub = async () => { throw new Error('some unrelated boot failure') }
    const iso = buildIsolator(patched, mountStub)
    let threw = null
    try { await iso.dshMobileMountRootIncludeTolerant({}, 'dsh', '/cfg/cordis.yml', [], undefined) } catch (error) { threw = error }
    check('② 无法识别的失败：原样抛出（不误吞、不空转重试）',
      threw !== null && /unrelated boot failure/.test(threw.message), threw === null ? '未抛出' : '')
  }
  // ⑥ 混合失败（第三方 + 官方）→ 必须整体失败，且不得为第三方做过任何 disable
  {
    const { calls, run } = mk([
      { id: 'live2d-pet', name: 'dsh-live2d-pets' },
      { id: 'core-thing', name: '@deepseek-ai/dsh-settings' },
    ])
    let threw = null
    try { await run() } catch (error) { threw = error }
    check('② 混合失败（第三方 + 官方）：整体仍失败（官方坏掉不得被第三方掩盖）', threw !== null)
    check('② 混合失败时不产生任何 disabled 重试（先判官方，不做半截隔离）',
      calls.length === 1, 'calls=' + calls.length)
  }
  // ⑦ 上限收敛：连续失败超过上限 → 仍失败且给完整清单
  {
    const many = Array.from({ length: 12 }, (_, i) => ({ id: 'third-' + i, name: 'third-party-plugin-' + i }))
    let attempt = 0
    const mountStub = async (ctx, configPath, patches) => {
      attempt += 1
      const already = patches.length
      if (already >= 9) throw importErrorFor(...many.slice(already, already + 3).map((f) => [f.id, f.name]))
      throw importErrorFor(...many.slice(already, already + 1).map((f) => [f.id, f.name]))
    }
    const iso = buildIsolator(patched, mountStub)
    let threw = null
    try { await iso.dshMobileMountRootIncludeTolerant({}, 'dsh', '/cfg/cordis.yml', [], undefined) } catch (error) { threw = error }
    check('② 超过隔离上限（8）：仍失败（不允许无限容忍）',
      threw !== null && /isolation limit/.test(threw.message), threw === null ? '未抛出' : threw.message.slice(0, 120))
    check('② 超限错误给出完整坏插件清单', threw !== null && /third-party-plugin-/.test(threw.message),
      threw === null ? '' : threw.message.slice(0, 160))
  }
  // ⑧ 收集器能解析 AggregateError 折叠的多条与 loader 的行格式
  {
    const iso = buildIsolator(patched, async () => {})
    const parsed = iso.dshMobileCollectEntryFailures(importErrorFor(['a', 'pkg-a'], ['b', '@deepseek-ai/x']))
    check('② 收集器解析 AggregateError 多条失败',
      parsed.length === 2 && parsed[0].id === 'a' && parsed[1].name === '@deepseek-ai/x', JSON.stringify(parsed))
    check('② 收集器过滤掉 bootstrap include 行本身',
      iso.dshMobileCollectEntryFailures(new Error('failed to apply loader entry include (cordis:include): x')).length === 0)
    check('② 出货判据：官方与移动侧为 true，用户自装为 false',
      iso.dshMobileIsShippedPlugin('@deepseek-ai/dsh-x') === true
      && iso.dshMobileIsShippedPlugin('@dsh-android/dsh-android-bridge') === true
      && iso.dshMobileIsShippedPlugin('dshmarketplace-plugin') === true
      && iso.dshMobileIsShippedPlugin('dsh-live2d-pets') === false)
    // 【0.14.1 D-1 反回归】已摘除的插件必须**不再**算出货：名单成员加载失败按产品回归 fail-loud，
    // 而它已不在注入集 ⇒ 老设备上任何残留挂载都会把引擎启动打挂（fail-loud 用错对象）。
    // 本断言的存在意义：将来再摘除插件时忘改名单，这里立刻红（本轮实测就是这样发现的）。
    check('② 已摘除插件不得留在出货名单（否则残留挂载会 fail-loud 打死启动）',
      iso.dshMobileIsShippedPlugin('@aiwayds/dsh-model-sync') === false
      && iso.dshMobileIsIsolatableEntry('@aiwayds/dsh-model-sync') === true)
    // 归属必须**可证**：路径/URL 形态不是「用户自装包」的证据，一律不可隔离（否则产品自身的
      // 相对路径条目坏掉会被静默跳过——这正是反向断言抓到的缺陷）。
    check('② 归属可证性：裸包名可隔离；相对/绝对路径、file:/其它 scheme 一律不可隔离',
      iso.dshMobileIsIsolatableEntry('dsh-live2d-pets') === true
      && iso.dshMobileIsIsolatableEntry('@dsh-android/dsh-foo') === false
      && iso.dshMobileIsIsolatableEntry('./local-plugin.mjs') === false
      && iso.dshMobileIsIsolatableEntry('../up/plugin.mjs') === false
      && iso.dshMobileIsIsolatableEntry('/abs/plugin.mjs') === false
      && iso.dshMobileIsIsolatableEntry('file:///tmp/plugin.mjs') === false
      && iso.dshMobileIsIsolatableEntry('cordis:include') === false
      && iso.dshMobileIsIsolatableEntry('') === false,
      ['./local-plugin.mjs', '../up/plugin.mjs', '/abs/plugin.mjs', 'file:///tmp/p.mjs', 'cordis:include', '']
        .map((n) => n + '=' + iso.dshMobileIsIsolatableEntry(n)).join(' '))
  }
  // ⑨ 反向断言：相对路径条目失败 → 仍响亮失败（归属不可证，不得被隔离）
  {
    const { run } = mk([{ id: 'local-thing', name: './local-plugin.mjs' }])
    let threw = null
    try { await run() } catch (error) { threw = error }
    check('② 归属不可证的失败（./ 路径条目）：必须仍抛出，不得静默跳过',
      threw !== null && /does not provide an export named/.test(threw.message),
      threw === null ? '未抛出（缺陷：被误隔离）' : '')
  }

  // ⑩ H-2（0.14.2-fx-2）判别力：装配清单**完整**时，用户自己挂的官方包必须可隔离。
  //
  // 这是本轮 H-2 的核心判据。真因（用户诊断包 → engine.log:138）：用户在 profile 里挂
  // `@deepseek-ai/dsh-mcp-client`（entry id = mcp-lark）连自己的 MCP server，按**包名前缀**判
  // 会把这条合法配置当成产品回归 ⇒ 拒绝隔离 ⇒ 引擎 boot 硬崩、exit=1。
  //
  // 判据必须成对，缺任一即失去判别力：
  //   (a) 用户挂的官方包（不在我们装配清单里）⇒ **可隔离**（引擎照常起、日志点名）；
  //   (b) 我们清单里的官方包/移动侧包 ⇒ **仍然 fail-loud**（不得削弱）。
  // (b) 已由 ② 的两个用例覆盖；这里补 (a)，且**必须**让清单完整（树里有上游 bundle 行），
  // 否则判据会回落到保守前缀规则而看不到修复。
  {
    const owned = mkdtempSync(join(tmpdir(), 'g3-owned-'))
    try {
      const rel = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai'
      for (const [sub, body] of [
        ['dsh-base', '- id: mcp-resources\n  name: \'@deepseek-ai/dsh-mcp-resources\'\n- id: settings\n  name: \'@deepseek-ai/dsh-settings\'\n'],
        ['dsh-web-app', '- id: workspace\n  name: \'@deepseek-ai/dsh-workspace\'\n'],
      ]) {
        const p = join(owned, rel, sub, 'cordis.patch.yml')
        mkdirSync(dirname(p), { recursive: true })
        writeFileSync(p, body)
      }
      const p3 = join(owned, 'home/.dsh/profiles/web/cordis.patch.yml')
      mkdirSync(dirname(p3), { recursive: true })
      writeFileSync(p3, '- id: android-bridge\n  name: \'@dsh-android/dsh-android-bridge\'\n')
      const targetOwned = join(owned, TARGET)
      mkdirSync(dirname(targetOwned), { recursive: true })
      writeFileSync(targetOwned, readFileSync(FIXTURE, 'utf8').replace(/\r\n/g, '\n'))
      const appliedOwned = spawnSync(process.execPath,
        [join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs'), owned, '--apply', '--scope', 'engine', '--only', 'boot-third-party-isolation-G3'],
        { encoding: 'utf8' })
      check('⑩ 清单完整树：G3 apply exits 0', appliedOwned.status === 0,
        (appliedOwned.stdout || appliedOwned.stderr || '').trim().split('\n').slice(-2).join(' | '))
      const patchedOwned = readFileSync(targetOwned, 'utf8')
      const isoOwned = buildIsolator(patchedOwned, async () => {})
      check('⑩ 清单完整（complete=true）——否则本条判据会回落到保守前缀规则',
        /"complete":true/.test(patchedOwned), patchedOwned.slice(patchedOwned.indexOf('DSH_MOBILE_ASSEMBLED_MANIFEST'), patchedOwned.indexOf('DSH_MOBILE_ASSEMBLED_MANIFEST') + 90))
      check('⑩ (a) 用户自挂的官方包 @deepseek-ai/dsh-mcp-client 可隔离（旧前缀判据下为 false = 本轮缺陷）',
        isoOwned.dshMobileIsIsolatableEntry('@deepseek-ai/dsh-mcp-client') === true,
        'isolatable=' + isoOwned.dshMobileIsIsolatableEntry('@deepseek-ai/dsh-mcp-client'))
      check('⑩ (b) 我们装配清单里的官方包仍 fail-loud（@deepseek-ai/dsh-settings / dsh-mcp-resources）',
        isoOwned.dshMobileIsShippedPlugin('@deepseek-ai/dsh-settings') === true
        && isoOwned.dshMobileIsShippedPlugin('@deepseek-ai/dsh-mcp-resources') === true
        && isoOwned.dshMobileIsIsolatableEntry('@deepseek-ai/dsh-settings') === false,
        JSON.stringify(['@deepseek-ai/dsh-settings', '@deepseek-ai/dsh-mcp-resources'].map((n) => n + '=' + isoOwned.dshMobileIsShippedPlugin(n))))
      check('⑩ (b) 移动侧 @dsh-android/* 与具名出货插件仍 fail-loud（清单完整时不得削弱）',
        isoOwned.dshMobileIsIsolatableEntry('@dsh-android/dsh-android-bridge') === false
        && isoOwned.dshMobileIsIsolatableEntry('dshmarketplace-plugin') === false,
        ['@dsh-android/dsh-android-bridge', 'dshmarketplace-plugin'].map((n) => n + '=' + isoOwned.dshMobileIsIsolatableEntry(n)).join(' '))
      check('⑩ (b) 归属不可证仍不可隔离（清单完整时同样）',
        isoOwned.dshMobileIsIsolatableEntry('./local-plugin.mjs') === false
        && isoOwned.dshMobileIsIsolatableEntry('file:///tmp/p.mjs') === false)
      // (a) 的端到端形态：引擎必须照常启动（不抛），且点名跳过。
      const callsOwned = []
      let attemptOwned = 0
      const mountOwned = async (ctx, cfg, patches) => {
        callsOwned.push({ patches: patches.map((p) => ({ ...p })) })
        attemptOwned += 1
        if (attemptOwned === 1) throw importErrorFor(['mcp-lark', '@deepseek-ai/dsh-mcp-client'])
        return undefined
      }
      const isoRun = buildIsolator(patchedOwned, mountOwned)
      let threwOwned = null
      try { await isoRun.dshMobileMountRootIncludeTolerant({}, 'dsh', '/cfg/cordis.yml', [], undefined) } catch (error) { threwOwned = error }
      check('⑩ (a) 端到端：用户挂的官方包失败 → 引擎照常启动（不抛）', threwOwned === null,
        threwOwned === null ? '' : String(threwOwned.message).slice(0, 120))
      check('⑩ (a) 端到端：确实隔离并点名该 entry（不是静默吞掉）',
        callsOwned.length === 2 && callsOwned[1].patches.length === 1
        && callsOwned[1].patches[0].name === '@deepseek-ai/dsh-mcp-client'
        && isoRun.warnings.some((w) => w.includes('@deepseek-ai/dsh-mcp-client')),
        'calls=' + callsOwned.length + ' warnings=' + isoRun.warnings.join(' | ').slice(0, 140))
    } finally {
      rmSync(owned, { recursive: true, force: true })
    }
  }

  console.log(failures.length === 0 ? '\nALL PASS' : '\nFAILED ' + failures.length + ': ' + failures.join('; '))
  process.exit(failures.length === 0 ? 0 : 1)
} finally {
  rmSync(scratch, { recursive: true, force: true })
  delete globalThis.__dshMobileBootSkippedPlugins
}
