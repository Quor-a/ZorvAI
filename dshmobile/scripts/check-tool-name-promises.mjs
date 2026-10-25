#!/usr/bin/env node
// check-tool-name-promises.mjs —— 工具名「承诺 vs 实现」门禁（0.14.1 块N）。
//
// 为什么需要（0.14.1 设备实测缺陷 B2）：
//   指引里承诺的工具**从未被实现**。`android_vdisplay_input` 出现在三处模型可见文案里
//   （manage 的 `android_screen_list` 描述、`android_ui_dump` 失败文案与坐标指引、bridge 的坐标模式指引）
//   与 Skill 文档中，但全仓没有任何 `defineTool` 声明它——模型照指引调用只会拿到 `unknown tool`。
//   「指引指向一条不存在的路」比拒绝更难排查：模型会把 unknown tool 当成自己参数写错，反复重试。
//
// 判据（机械可判、零启发式）：
//   1) 注册集 = 插件 src 里所有**声明位**的 `android_*` 字面量——`键: 'android_x'` 形态
//      （含 `name:`、以及 contract.ts 里的 `tier: 'android_browser_tier'` 这类名册键）
//      ∪ `const *_TOOL* = 'android_x'` 常量形态（如 CAPABILITY_TOOL_NAME）。
//   2) 提及集 = 插件 src 里出现过的所有 `android_*` 标识符（描述文案、guidance、注释都算）。
//   3) 提及集 ⊆ 注册集；例外必须逐条写进 ALLOW 并注明理由。
//
// 实测基线（2026-09-19）：26 声明 / 26 提及 / 0 漏 —— 不加白名单即为全绿；加工具不同批补声明即红。
//
// 用法：
//   node scripts/check-tool-name-promises.mjs             # 两仓布局自适应
//   node scripts/check-tool-name-promises.mjs --self-test # 判别力自证（承诺未实现必红）
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs'
import { dirname, join, resolve, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const APK_ROOT = resolve(HERE, '..')

/**
 * 允许「被提及但未声明」的例外。**只允许是真实存在的非本仓工具**，且必须写明理由；
 * 空数组是本仓的常态——加任何一条都应在 PR 里被质疑。
 */
const ALLOW = []

/** 声明位形态 A：`name: 'android_x'` / `tier: 'android_browser_tier'`（名册键）。 */
const DECL_KEY = /\b[\w$]+:\s*'(android_[a-z0-9_]+)'/g
/** 声明位形态 B：`export const CAPABILITY_TOOL_NAME = 'android_capabilities'`。 */
const DECL_CONST = /(?:export\s+)?const\s+\w*TOOL\w*\s*=\s*'(android_[a-z0-9_]+)'/g
/** 提及：任意位置的 `android_*` 标识符。 */
const MENTION = /\bandroid_[a-z][a-z0-9_]*/g

export function collectRegistry(texts) {
  const out = new Set()
  for (const t of texts) {
    for (const m of t.matchAll(DECL_KEY)) out.add(m[1])
    for (const m of t.matchAll(DECL_CONST)) out.add(m[1])
  }
  return out
}

export function collectMentions(texts) {
  const out = new Set()
  for (const t of texts) for (const m of t.matchAll(MENTION)) out.add(m[0])
  return out
}

/** @returns 提及但未声明的名字（已剔除 ALLOW）。 */
export function findPromises(registry, mentions, allow = ALLOW) {
  const allowed = new Set(allow.map((e) => e.name))
  return [...mentions].filter((n) => !registry.has(n) && !allowed.has(n)).sort()
}

function pluginSourceFiles(root) {
  const bases = [join(root, 'plugins'), join(root, '..', 'plugins')]
  const pluginsDir = bases.find((b) => existsSync(b))
  if (pluginsDir === undefined) throw new Error('找不到 plugins/ 目录：' + bases.join(' | '))
  const files = []
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, entry.name)
      if (entry.isDirectory()) {
        if (['node_modules', 'lib', 'dist', 'test'].includes(entry.name)) continue
        walk(p)
      } else if (/\.(ts|mjs|js)$/.test(entry.name)) {
        files.push(p)
      }
    }
  }
  for (const name of readdirSync(pluginsDir)) {
    const src = join(pluginsDir, name, 'src')
    if (existsSync(src) && statSync(src).isDirectory()) walk(src)
  }
  return { pluginsDir, files }
}

function selfTest() {
  const cases = [
    {
      name: '正例：声明与提及一致',
      texts: ["  ctx.tools.register(defineTool({ name: 'android_ui_dump', description: '用 android_ui_dump 取树' }))"],
      expect: [],
    },
    {
      name: '反例（0.14.1 B2 原形）：文案承诺了没实现的工具',
      texts: ["  description: '纯 Shizuku 下改用 android_vdisplay_input（tap/swipe/keyevent/text）'"],
      expect: ['android_vdisplay_input'],
    },
    {
      name: '反例：常量声明位也算数（不得误报 android_capabilities）',
      texts: ["export const CAPABILITY_TOOL_NAME = 'android_capabilities'", "  text: '先调 android_capabilities'"],
      expect: [],
    },
    {
      name: '反例：名册键形态也算数（browser contract 的 tier 项）',
      texts: ["  tier: 'android_browser_tier',", "  text: '用 android_browser_tier 看档位'"],
      expect: [],
    },
  ]
  let failed = 0
  for (const c of cases) {
    const got = findPromises(collectRegistry(c.texts), collectMentions(c.texts))
    if (JSON.stringify(got) !== JSON.stringify(c.expect)) {
      failed += 1
      console.error(`SELF-TEST FAIL：${c.name} 期望 ${JSON.stringify(c.expect)} 实得 ${JSON.stringify(got)}`)
    }
  }
  if (failed > 0) {
    console.error(`CHECK-TOOL-NAME-PROMISES SELF-TEST FAILED（${failed}/${cases.length}）`)
    process.exit(1)
  }
  console.log(`CHECK-TOOL-NAME-PROMISES SELF-TEST PASSED（${cases.length} 例：正例 + 三个反例族）`)
}

function main() {
  if (process.argv.includes('--self-test')) {
    selfTest()
    return
  }
  const { pluginsDir, files } = pluginSourceFiles(APK_ROOT)
  const texts = files.map((f) => readFileSync(f, 'utf8'))
  const registry = collectRegistry(texts)
  const mentions = collectMentions(texts)
  const missing = findPromises(registry, mentions)
  if (missing.length > 0) {
    console.error('CHECK-TOOL-NAME-PROMISES FAILED：以下工具名在文案/指引里被承诺，但全仓没有声明位——')
    for (const name of missing) {
      const where = files.filter((f, i) => texts[i].includes(name)).slice(0, 3).map((f) => relative(APK_ROOT, f))
      console.error(`  ${name}  ← 出现在：${where.join(' , ')}`)
    }
    console.error('  修法二选一：① 补实现（defineTool 声明该名字）；② 若该工具不存在也不该存在，改指引文案。')
    console.error('  **不得**把名字加进 ALLOW 了事（那是把「指引指向不存在的路」制度化）。')
    process.exit(1)
  }
  console.log(`CHECK-TOOL-NAME-PROMISES PASSED（声明 ${registry.size} 个 · 提及 ${mentions.size} 个 · 漏报 0${ALLOW.length > 0 ? ` · 白名单 ${ALLOW.length} 条` : ' · 无白名单'}）`)
  console.log(`  （扫描 ${files.length} 个插件源文件，plugins 目录：${relative(APK_ROOT, pluginsDir) || '.'}）`)
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
}
