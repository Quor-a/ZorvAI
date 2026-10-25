#!/usr/bin/env node
// check-dead-tokens.mjs — 死 token 防漂移门禁（0.14.2 D3 / 阶段 B6）。
//
// 背景（D3 实锤）：开发者选项页里注入的按钮/输入框背景**变白**。真因是我们在 CSS 里大量引用
// **本代已不存在**的设计 token，且回退值是亮色硬编码：
//   border: 1px solid var(--dsw-alias-border-strong, #ccc);   /* token 空 -> #ccc */
//   background: var(--dsw-alias-bg-elevated, #fff);          /* token 空 -> #fff  <- 变白 */
// token 不存在时整条声明失效（实测 `var(--dsw-alias-bg-elevated)` = rgba(0,0,0,0)），
// 与主题无关的「白底白字」就是这么来的；而上游改名（bg/border 系列 -> `bg-layer-{1,2,3}` /
// `border-l{1..4}`）不会有任何编译期或运行期报错。
//
// 判据：我们自己的 CSS/样式字符串里引用的 `--dsw-*` 令牌，必须在上游**现存令牌集合**里；
// 引用不存在的令牌即 exit 1 并逐条列出 `文件:行`。
//
// 上游令牌真源 = contract.upstreamRepo/packages/client/ui-theme/src/styles/*.css（该目录是设计 token 的唯一定义面：
// design-platform.css 定义别名层，gradient-shadow-text.css 定义字体层，均需计入）。上游树不在场时
// **跳过**（与 check-contract.mjs / check-engine-overlay.mjs 同风格），并打印 SKIP 计数；
// `--require` 时 SKIP 判红（发布链不得以 SKIP 结案）——**不恒绿**。
//
// 允许清单 [ALLOW]：本仓自己**定义**的同名局部变量（在 `:root` / 注入的 style 里给出值）不算死引用；
// 每条都必须写在下面的清单里并写明原因（默认拒绝）。
//
// 用法：node scripts/check-dead-tokens.mjs [--require] [--self-test] [--root <upstream-styles-dir>]
// 退出码：0 = 通过（或显式 SKIP）；1 = 有死 token / --require 下有 SKIP；2 = 用法错误。
import { createHash } from 'node:crypto'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = dirname(HERE)
const argv = process.argv.slice(2)
const REQUIRE = argv.includes('--require')

let skipped = 0
const skip = (msg) => { skipped += 1; console.log('SKIP(#' + skipped + ')  ' + msg) }

/** 扫描面：我们自己的 CSS 与样式字符串所在目录（相对协调仓根；壳侧自包含树用同名相对路径）。 */
const SCAN_DIRS = [
  'dsh-client-ui-responsive/src',
  'plugins',
  'vendor/dsh-undo-savepoint',
  'vendor/dshmarketplace-plugin',
]
/**
 * 上游令牌定义面（目录；只读）。
 *
 * 只取 ui-theme 的 stylesheet 目录：那是设计 token 的**全局**定义面（design-platform.css 定义别名层，
 * gradient-shadow-text.css 定义字体层）。**刻意不扫**别处 `*.module.css` 里的 `--dsw-x:`——
 * 那些是组件作用域内的局部自定义属性，对全局引用不构成「已定义」，把它们算进来会把真死引用洗绿。
 */
const contract = JSON.parse(readFileSync(join(HERE, 'contract.json'), 'utf8'))
if (typeof contract.upstreamRepo !== 'string' || !contract.upstreamRepo.trim()) {
  throw new Error('contract.upstreamRepo 缺席：不得回落到旧 dsh/ 树')
}
const UPSTREAM_TOKEN_DIR = join(contract.upstreamRepo, 'packages/client/ui-theme/src/styles')

/**
 * 允许清单：引用但不来自上游的令牌 —— 必须是**我们自己定义**的局部变量。
 * 每条都要写明定义位置与原因；新增条目需要同样的证据（默认拒绝）。
 */
const ALLOW = new Map([
  // 例：['--dsw-local-example', 'dsh-client-ui-responsive/src/client/x.css.ts 的 :root 注入定义'],
])

/**
 * 上游自带缺陷豁免（**不是** ALLOW，语义完全不同）。
 *
 * 上游 0.2.0-rc.2 自身把 `ui-sidebar-browser` 的 Browser.module.css 逐字节搬到本仓（见
 * dsh-client-ui-responsive/src/client/mobile/upstream-browser/SOURCE.json 的 sha256 锁定），
 * 而该文件引用的 `--dsw-alias-label-quaternary` 已被上游自己的 ui-theme 删除 ——
 * 即上游自身就是「引用不存在的令牌」。我方既要跟上游逐字节一致（SOURCE.json 哈希锁，
 * 改一个字节 SOURCE 校验就红），又不能把这条真缺陷悄悄洗绿。
 *
 * 故此处**不是**「容忍配额」：每条豁免都必须同时满足
 *   ① 引用的文件在 VERBATIM_VENDORS 里（上游逐字节 vendored 面），
 *   ② 该文件的 sha256 与 SOURCE.json 声明一致（文件一旦偏离上游，豁免立即失效并判红）。
 * 任何一条不满足都不豁免 —— 新增条目需要同样两条证据，默认拒绝。
 */
const VERBATIM_VENDORS = new Map([
  [
    'dsh-client-ui-responsive/src/client/mobile/upstream-browser/view/Browser.module.css',
    '787ffc87ef674562ef1758e3d7452809ebb19cd4d5853a6dc4207c7b3c6c4039',
  ],
])
const UPSTREAM_NATIVE_DEFECTS = new Map([
  [
    '--dsw-alias-label-quaternary',
    '上游 0.2.0-rc.2 的 ui-sidebar-browser/Browser.module.css 自身引用，而上游 ui-theme 已删除该令牌；'
      + '我方按 SOURCE.json 逐字节 vendor 该文件，改字节会让 SOURCE 哈希校验失败。上游修复后随之消失。',
  ],
])

/**
 * 判定一条死引用是否属于「上游逐字节 vendored 文件自带缺陷」。
 *
 * @param ref - @BQ@{ token, file, line }@BQ@ 引用。
 * @returns 命中且哈希一致时为豁免理由字符串，否则 null。
 */
function upstreamNativeDefect(ref) {
  if (!UPSTREAM_NATIVE_DEFECTS.has(ref.token)) return null
  const expected = VERBATIM_VENDORS.get(ref.file)
  if (expected === undefined) return null
  const abs = resolveRepoPath(ref.file)
  if (abs === null) return null
  const actual = createHash('sha256').update(readFileSync(abs)).digest('hex')
  if (actual !== expected) {
    console.error('VERBATIM 哈希失配：' + ref.file + ' 声明 ' + expected + ' 实测 ' + actual
      + ' —— 该文件已偏离上游，上游缺陷豁免不再适用，按死引用判红')
    return null
  }
  return UPSTREAM_NATIVE_DEFECTS.get(ref.token)
}

/** 布局无关定位：协调仓根写 `dsh-mobile-apk/...`；壳侧自包含仓落到同名相对路径。 */
function resolveRepoPath(rel) {
  const cands = rel.startsWith('dsh-mobile-apk/') ? [rel, rel.slice('dsh-mobile-apk/'.length)] : [rel]
  const hit = cands.find((c) => existsSync(join(ROOT, c)))
  return hit === undefined ? null : join(ROOT, hit)
}

/** 递归收集符合后缀的文件。 */
function walk(dir, out, suffixes) {
  if (!existsSync(dir)) return out
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (name === 'node_modules' || name === 'lib' || name === '.git') continue
    const st = statSync(full)
    if (st.isDirectory()) walk(full, out, suffixes)
    else if (suffixes.some((s) => name.endsWith(s))) out.push(full)
  }
  return out
}

/**
 * 抽取上游现存令牌集合（所有 `*.css` 里 `--dsw-x:` 形式的定义）。
 * @param dir - 上游样式目录绝对路径。
 * @returns 令牌名集合。
 */
export function definedTokens(dir) {
  const out = new Set()
  for (const file of walk(dir, [], ['.css'])) {
    const text = readFileSync(file, 'utf8')
    for (const m of text.matchAll(/^\s*(--dsw-[a-z0-9-]+)\s*:/gm)) out.add(m[1])
  }
  return out
}

/**
 * 抽取一份文本里的全部 `--dsw-*` **引用**：`var(--dsw-x)` / 字符串字面量里的 `--dsw-x`。
 * 定义处（`--dsw-x:`）不算引用 —— 那是我们自己给出的值，不会「取空」。
 * @param text - 文件全文。
 * @returns `{ token, line }` 列表（1-based 行号）。
 */
export function referencedTokens(text) {
  const out = []
  const lines = text.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    for (const m of line.matchAll(/--dsw-[a-z0-9-]+/g)) {
      const after = line.slice(m.index + m[0].length)
      if (/^\s*:/.test(after)) continue // 定义处，不是引用
      out.push({ token: m[0], line: i + 1 })
    }
  }
  return out
}

/**
 * 判据核心（纯函数，供主流程与 @BQ@--self-test@BQ@ 共用）。
 *
 * @param references - @BQ@{ token, file, line }@BQ@ 列表（file 已是可读路径）。
 * @param defined - 上游现存令牌集合。
 * @param allow - 允许清单（令牌 -> 理由）。
 * @returns @BQ@{ dead: Map<token, string[]>, usedAllow: Set<string> }@BQ@——死引用站点与实际用到的允许条目。
 */
export function audit(references, defined, allow = new Map()) {
  const dead = new Map()
  const usedAllow = new Set()
  for (const ref of references) {
    if (defined.has(ref.token)) continue
    if (allow.has(ref.token)) { usedAllow.add(ref.token); continue }
    if (!dead.has(ref.token)) dead.set(ref.token, [])
    dead.get(ref.token).push(ref.file + ':' + ref.line)
  }
  return { dead, usedAllow }
}

if (argv.includes('--self-test')) {
  // ① 引用抽取：真引用必须命中；定义处、注释里的裸名字不得当成引用。
  const sample = [
    '.a { border: 1px solid var(--dsw-alias-border-strong, #ccc); }',
    '.b { background: var(--dsw-alias-bg-elevated, #fff); }',
    ':root { --dsw-local-defined: #000; }',
    '.c { color: var(--dsw-alias-label-primary); }',
  ].join('\n')
  const got = referencedTokens(sample).map((r) => r.token)
  const want = ['--dsw-alias-border-strong', '--dsw-alias-bg-elevated', '--dsw-alias-label-primary']
  const okRefs = JSON.stringify(got) === JSON.stringify(want)
  const okDefsSkipped = !got.includes('--dsw-local-defined')

  // ② 判据两向自证（本门禁不是「只能红」也不是「只能绿」）：
  //    正向 —— 引用全在上游定义集合里 ⇒ 必须通过；
  //    反向 —— 混入一条已不存在的 token（D3 实锤的 bg-elevated）⇒ 必须判红且点名该 token。
  const defs = new Set(['--dsw-alias-label-primary', '--dsw-alias-border-l3'])
  const clean = audit([{ token: '--dsw-alias-label-primary', file: 'x.css', line: 1 }], defs, new Map())
  const dirty = audit([
    { token: '--dsw-alias-label-primary', file: 'x.css', line: 1 },
    { token: '--dsw-alias-bg-elevated', file: 'y.css', line: 7 },
  ], defs, new Map())
  const okGreen = clean.dead.size === 0
  const okRed = dirty.dead.size === 1 && dirty.dead.has('--dsw-alias-bg-elevated')
    && dirty.dead.get('--dsw-alias-bg-elevated')[0] === 'y.css:7'
  //    ③ 允许清单必须真的豁免（否则 ALOW 会成为「永远红」的摆设）。
  const allowed = audit([{ token: '--dsw-alias-bg-elevated', file: 'y.css', line: 7 }], defs,
    new Map([['--dsw-alias-bg-elevated', '自有定义']]))
  const okAllow = allowed.dead.size === 0 && allowed.usedAllow.has('--dsw-alias-bg-elevated')

  // ④ 允许清单不参与判红：定义集合为空的合成引用必须照样判红（防「空定义面 = 全豁免」的恒绿形态）。
  const emptyDefs = audit([{ token: '--dsw-alias-bg-elevated', file: 'y.css', line: 7 }], new Set(), new Map())
  const okNoDefinitionFace = emptyDefs.dead.size === 1

  const allPass = okRefs && okDefsSkipped && okGreen && okRed && okAllow && okNoDefinitionFace
  console.log((allPass ? 'DEAD-TOKENS SELF-TEST PASSED' : 'DEAD-TOKENS SELF-TEST FAILED')
    + '（引用抽取 ' + got.length + '/3；定义处不计入 ' + (okDefsSkipped ? '是' : '否')
    + '；全命中判绿 ' + (okGreen ? '是' : '否')
    + '；混入死 token 判红且点名 ' + (okRed ? '是' : '否')
    + '；允许清单豁免 ' + (okAllow ? '是' : '否')
    + '；定义面为空仍判红 ' + (okNoDefinitionFace ? '是' : '否') + '）')
  process.exit(allPass ? 0 : 1)
}

const rootArg = argv.indexOf('--root')
if (rootArg >= 0 && (!argv[rootArg + 1] || argv[rootArg + 1].startsWith('--'))) {
  console.error('--root 需要上游样式定义目录'); process.exit(2)
}
const upstreamRel = rootArg >= 0 ? resolve(ROOT, argv[rootArg + 1]) : resolveRepoPath(UPSTREAM_TOKEN_DIR)
if (upstreamRel === null || !existsSync(upstreamRel)) {
  skip('上游令牌定义面不在场（' + UPSTREAM_TOKEN_DIR + '）：无判据，跳过死 token 对账（不计入绿）')
  console.log('SKIP=' + skipped)
  if (REQUIRE) { console.error('CHECK-DEAD-TOKENS FAILED：--require 下不得以 SKIP 结案'); process.exit(1) }
  process.exit(0)
}

const defined = definedTokens(upstreamRel)
if (defined.size === 0) {
  console.error('CHECK-DEAD-TOKENS FAILED：上游样式目录在场但解析到 0 个令牌（定义面口径失效，不得判绿）')
  process.exit(1)
}

const scanned = []
for (const rel of SCAN_DIRS) {
  const abs = resolveRepoPath(rel)
  if (abs === null) continue
  walk(abs, scanned, ['.css', '.ts', '.tsx'])
}
if (scanned.length === 0) {
  skip('扫描面为空（' + SCAN_DIRS.join(', ') + ' 都不在场）：无判据，跳过')
  console.log('SKIP=' + skipped)
  if (REQUIRE) { console.error('CHECK-DEAD-TOKENS FAILED：--require 下不得以 SKIP 结案'); process.exit(1) }
  process.exit(0)
}

const references = []
for (const file of scanned) {
  const text = readFileSync(file, 'utf8')
  const rel = relative(ROOT, file).replace(/\\/g, '/')
  for (const hit of referencedTokens(text)) references.push({ token: hit.token, file: rel, line: hit.line })
}
// 上游逐字节 vendored 文件自带的缺陷：单独剥离并记账，绝不并入 ALLOW（语义不同，且这里必须哈希一致才认）。
const exemptRefs = []
const gatedReferences = []
for (const ref of references) {
  const reason = upstreamNativeDefect(ref)
  if (reason === null) gatedReferences.push(ref)
  else exemptRefs.push({ ...ref, reason })
}
const { dead, usedAllow } = audit(gatedReferences, defined, ALLOW)
for (const token of UPSTREAM_NATIVE_DEFECTS.keys()) {
  if (!exemptRefs.some((r) => r.token === token)) {
    console.log('WARN  上游缺陷豁免条目未被用到（上游可能已修复，可清理）: ' + token)
  }
}
// 允许清单腐化检测：声明了却没人用的条目意味着「问题已消失」或「写错了名字」，两种都该清掉——
// 否则清单会越滚越长，最终成为「什么都豁免」的摆设。
for (const token of ALLOW.keys()) {
  if (!usedAllow.has(token)) console.log('WARN  ALLOW 条目未被用到（可清理）: ' + token)
}

console.log('上游令牌 ' + defined.size + ' 个（' + UPSTREAM_TOKEN_DIR + '）；扫描 ' + scanned.length + ' 个文件、' + references.length + ' 处引用')
if (exemptRefs.length > 0) {
  console.log('上游自带缺陷豁免 ' + exemptRefs.length + ' 处（逐字节 vendored + 哈希一致，非 ALLOW）：')
  for (const ref of exemptRefs) console.log('  ' + ref.token + ' @ ' + ref.file + ':' + ref.line + ' —— ' + ref.reason)
}
if (dead.size === 0) {
  console.log('CHECK-DEAD-TOKENS PASSED（引用 ' + references.length + ' 处全部命中上游现存令牌'
    + (exemptRefs.length > 0 ? '；另 ' + exemptRefs.length + ' 处为上游自带缺陷豁免' : '') + '）')
  process.exit(0)
}
// 死引用一律判红，不设「容忍配额」：配额的存在只会让下一处死 token 悄悄挤进允许范围，
// 而 D3 的形态恰恰是「引用不存在的令牌 + 亮色硬编码回退」，看代码完全看不出来。
console.error('CHECK-DEAD-TOKENS FAILED（' + dead.size + ' 个已不存在的令牌被引用）：')
for (const [token, sites] of [...dead.entries()].sort()) {
  console.error('  ' + token + '（' + sites.length + ' 处）' + (ALLOW.has(token) ? '' : ''))
  for (const site of sites.slice(0, 5)) console.error('      ' + site)
  if (sites.length > 5) console.error('      … 另 ' + (sites.length - 5) + ' 处')
}
console.error('（token 已被上游改名/移除 ⇒ var(--dsw-x, 亮色回退) 整条声明失效；深色主题下就是「白底白字」。')
console.error(' 改用上游现存令牌，或在本脚本 ALLOW 里登记自有定义的令牌。）')
process.exit(1)
