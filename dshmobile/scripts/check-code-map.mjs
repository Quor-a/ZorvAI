#!/usr/bin/env node
// check-code-map.mjs — 执行地图（EXECUTION-MAP.md）覆盖与锚点门禁。
//
// 目的：把「代码执行地图」变成**不会静默过期**的资产。地图文档是人写的说明面，
// 最容易的失效方式是「新加了一个源文件，没人把它挂到任何查点上」——此后排查的人
// 按图索骥永远找不到它。本门禁守四件事：
//   1. 覆盖：app/src、plugins/*/{src,test}、三个子仓 src、构建与门禁脚本 —— 每个文件
//      至少被覆盖账本（<!-- COVERAGE --> 块）里的一条路径或 glob 命中；
//   2. 锚点：文档里所有 `path:line` 必须真实存在（apk 仓优先，其次协调仓同级路径），
//      且行号不超过文件长度；
//   3. 编号：主表（<!-- TABLE-ROW --> 块）里的查点 ID 与 `### <ID>` 章节必须一一对应；
//      耦合边（<!-- COUPLING --> 块）两端的 ID 必须都已登记；
//   4. 排版：mermaid 围栏成对、块内不得出现 style/classDef/linkStyle（渲染兼容），
//      文档不得含 emoji（仓铁律）。
//
// 用法：
//   node scripts/check-code-map.mjs                 # 校验（默认文档 docs/AGENTS/EXECUTION-MAP.md）
//   node scripts/check-code-map.mjs --list          # 只打印账本盘点，不判红
//   node scripts/check-code-map.mjs --doc <path>    # 换文档（调试用）
//   node scripts/check-code-map.mjs --self-test     # 无仓库依赖的判别力自检（含故意失败样本）
// 退出码：0 = 通过；1 = 有失败项；2 = 前置不满足（文档缺失/文档缺账本块）。
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve as resolvePath } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
/**
 * 仓根定位（布局无关）。本门禁的输入全部在 **apk 仓**（app/src、plugins、EXECUTION-MAP.md）。
 * 两种布局都必须能跑，因为门禁会被从两个根调用：
 *   - apk 自包含根：脚本在 `<apk>/scripts/` ⇒ HERE 的父目录就是 apk 仓；
 *   - 协调仓根：脚本镜像在 `<coord>/scripts/`，apk 树在 `<coord>/dsh-mobile-apk/`。
 * 旧实现直接取 dirname(HERE) 当基线仓，于是从协调仓根跑时把**协调仓**当基线：
 * 全域文件 0、覆盖账本 967 项全判「路径不存在」而失败（exit 2）。而构建链恰好是从
 * 协调仓根逐条调用门禁的，所以这条布局缺口会让本门禁永远进不了声明集。
 */
const SELF = dirname(HERE)
const looksLikeApkRepo = (p) => existsSync(join(p, 'app', 'src', 'main', 'AndroidManifest.xml'))
const REPO = looksLikeApkRepo(SELF)
  ? SELF
  : (looksLikeApkRepo(join(SELF, 'dsh-mobile-apk')) ? join(SELF, 'dsh-mobile-apk') : SELF)
const PARENT = dirname(REPO)
const argv = process.argv.slice(2)
const argOf = (n, d) => { const i = argv.indexOf('--' + n); return i >= 0 ? (argv[i + 1] ?? d) : d }
const DOC_REL = argOf('doc', 'docs/AGENTS/EXECUTION-MAP.md')
const LIST_ONLY = argv.includes('--list')
const SELF_TEST = argv.includes('--self-test')
/** CI 等价性检查：apk 仓在被独立检出时没有协调仓父目录，--no-parent 用来本地预演那种情形。 */
const NO_PARENT = argv.includes('--no-parent')

/** 覆盖全域：地图必须能定位到的文件集合（构建产物与第三方目录除外）。 */
const UNIVERSE = [
  { root: 'app/src', keep: (p) => p === 'main/AndroidManifest.xml' || p.endsWith('.kt') },
  { root: 'plugins', keep: (p) => /^[^/]+\/(src|test)\/[^/]+/.test(p) && /\.(ts|tsx|mjs|js)$/.test(p) },
  { root: 'dsh-client-ui-responsive/src', keep: (p) => /\.(ts|tsx|css)$/.test(p) },
  { root: 'dsh-host-web-compat/lib', keep: (p) => /\.(js|ts|d\.ts)$/.test(p) },
  { root: 'dsh-host-web-compat/scripts', keep: (p) => /\.(mjs|js)$/.test(p) },
  { root: 'dsh-shell-termux/src', keep: (p) => /\.ts$/.test(p) },
  {
    root: 'scripts',
    keep: (p) => !p.includes('/') && /\.(mjs|ps1|py|sh)$/.test(p)
      && /^(check-|verify-|build-|inject-|make-snapshot|relocate-snapshot|update-snapshot-patch)/.test(p),
  },
  { root: 'scripts/patches', keep: (p) => /\.(mjs|js)$/.test(p) },
  { root: 'scripts/snapshot-config', keep: () => true },
]
const SKIP_DIRS = new Set(['node_modules', '.git', 'build', 'out', 'release', '.gradle', '__pycache__', '.deploy-tmp'])

/** glob → 正则：支持 `**`（跨目录）、`*`（单层内）、`?`；路径一律 `/` 分隔且相对仓根。 */
export function globToRegExp(pattern) {
  let re = ''
  for (let i = 0; i < pattern.length; i += 1) {
    const c = pattern[i]
    if (c === '*') {
      if (pattern[i + 1] === '*') {
        i += 1
        if (pattern[i + 1] === '/') { i += 1; re += '(?:.*/)?' } else { re += '.*' }
      } else re += '[^/]*'
    } else if (c === '?') re += '[^/]'
    else re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&')
  }
  return new RegExp('^' + re + '$')
}

const norm = (p) => p.replace(/\\/g, '/').replace(/^\.\//, '').replace(/^dsh-mobile-apk\//, '')

const ID_SHAPE = /^[A-Za-z]{1,3}\d{1,3}$/

/** 锚点与耦合证据共用的扩展名口径（避免两处漂移）。
 *  写法纪律：这里刻意不用 `\w`/`\d`/`\.`（字符串字面量里的反斜杠转义在落盘/传输环节
 *  有过被吞成单字符的实锤，正则静默失效）——一律用等价的显式字符类。 */
const EXT = 'kt|kts|ts|tsx|mjs|js|py|ps1|sh|json|xml|yml|yaml|md'
const PATH_GROUP = '((?:[A-Za-z0-9_.@-]+/)*[A-Za-z0-9_.@-]+[.](?:' + EXT + '))'
const LINE_GROUP = ':([0-9]+)'
const ANCHOR_RE = new RegExp('(?:^|[^A-Za-z0-9_./:@-])' + PATH_GROUP + LINE_GROUP, 'g')
const EVIDENCE_RE = new RegExp(PATH_GROUP + LINE_GROUP)

/** 按 `## <序号>. <标题前缀>` 切出章节正文（可见表格按章节解析，注释块作为兼容来源）。 */
export function sectionOf(text, titlePrefix) {
  const lines = text.split('\n')
  let start = -1
  for (let i = 0; i < lines.length; i += 1) {
    if (/^##\s+/.test(lines[i]) && lines[i].replace(/^##\s+/, '').startsWith(titlePrefix)) { start = i + 1; break }
  }
  if (start < 0) return ''
  const out = []
  for (let i = start; i < lines.length; i += 1) {
    if (/^##\s+/.test(lines[i])) break
    out.push(lines[i])
  }
  return out.join('\n')
}

const tableRows = (chunk) => {
  const rows = []
  for (const line of chunk.split('\n')) {
    const t = line.trim()
    if (!t.startsWith('|')) continue
    const cells = t.replace(/^\||\|$/g, '').split('|').map((s) => s.trim())
    if (cells.length < 2) continue
    if (/^-{2,}$/.test(cells[0]) || cells[0] === '' || cells[0] === '从' || cells[0] === 'ID') continue
    rows.push(cells)
  }
  return rows
}

/** 解析文档：主表 ID、章节 ID、覆盖账本、耦合边、锚点、mermaid。 */
export function parseDoc(text) {
  const block = (name) => {
    const out = []
    const re = new RegExp('<!--\\s*' + name + '\\s*([\\s\\S]*?)-->', 'g')
    let m
    while ((m = re.exec(text)) !== null) out.push({ at: m.index, body: m[1].trim() })
    return out
  }
  // 主表：① 可见表格（§ 查点主表 章节里首列是查点 ID 的行）；② 兼容 <!-- TABLE-ROW --> 注释块
  const tableIds = []
  for (const cells of tableRows(sectionOf(text, '2. 查点主表'))) if (ID_SHAPE.test(cells[0])) tableIds.push(cells[0])
  for (const b of block('TABLE-ROW')) {
    for (const line of b.body.split('\n')) {
      const cells = line.trim().replace(/^\||\|$/g, '').split('|').map((s) => s.trim())
      if (cells.length >= 2 && ID_SHAPE.test(cells[0])) tableIds.push(cells[0])
    }
  }
  const sectionIds = [...text.matchAll(/^#{3,6}\s+([A-Za-z]{1,3}\d{1,3})\s+\S/gm)].map((m) => m[1])
  const coverage = []
  for (const b of block('COVERAGE')) {
    for (const line of b.body.split('\n')) {
      const t = line.trim()
      if (t === '' || t.startsWith('#')) continue
      if (t.startsWith('glob:')) coverage.push({ glob: norm(t.slice(5).trim()) })
      else coverage.push({ path: norm(t) })
    }
  }
  // 耦合边：① 可见表格（§ 耦合矩阵）；② 兼容 <!-- COUPLING --> 注释块里的 `A -> B | 关系 | 证据`
  const couplings = []
  for (const cells of tableRows(sectionOf(text, '4. 耦合矩阵'))) {
    if (cells.length < 3) continue
    couplings.push({
      from: cells[0], to: cells[1], note: cells.slice(2, -1).join(' | '), evidence: cells[cells.length - 1],
      raw: cells.join(' | '), parsed: ID_SHAPE.test(cells[0]),
    })
  }
  for (const b of block('COUPLING')) {
    for (const line of b.body.split('\n')) {
      const t = line.trim()
      if (t === '' || t.startsWith('#')) continue
      const [edge, note, evidence] = t.split('|').map((s) => (s ?? '').trim())
      const mm = edge.match(/^([A-Za-z]{1,3}\d{1,3})\s*->\s*([A-Za-z]{1,3}\d{1,3})$/)
      couplings.push({ from: mm ? mm[1] : edge, to: mm ? mm[2] : '', note, evidence, raw: t, parsed: !!mm })
    }
  }
  const anchors = []
  // 负向后顾：路径前不能是路径字符或 `:`（后者用来跳过 `coord:docs/...:12` 这类跨仓引用）
  for (const m of text.matchAll(ANCHOR_RE)) {
    anchors.push({ path: norm(m[1]), line: Number(m[2]) })
  }
  const mermaid = []
  const fences = text.split('```')
  for (let i = 1; i < fences.length; i += 2) {
    const body = fences[i]
    if (body.startsWith('mermaid')) mermaid.push(body)
  }
  return { tableIds, sectionIds, coverage, couplings, anchors, mermaid, fenceCount: fences.length - 1 }
}

const EMOJI_RE = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{26FF}\u{FE0F}\u{1F1E6}-\u{1F1FF}]/u

/**
 * 核心判定：对给定文档文本 + 注入的文件系统视图做检查。
 * fsView = { exists(absOrRel) => bool, lineCount(rel) => number|null }（相对仓根）。
 */
export function checkDoc(text, universe, fsView) {
  const issues = []
  const doc = parseDoc(text)
  const push = (kind, detail) => issues.push({ kind, detail })

  if (doc.coverage.length === 0) push('no-ledger', '文档缺少 <!-- COVERAGE --> 账本块')
  if (doc.tableIds.length === 0) push('no-table', '文档缺少 <!-- TABLE-ROW --> 主表行')

  // 1. 覆盖
  const exact = new Set(doc.coverage.filter((c) => c.path).map((c) => c.path))
  const globs = doc.coverage.filter((c) => c.glob).map((c) => ({ src: c.glob, re: globToRegExp(c.glob) }))
  for (const f of universe) {
    if (exact.has(f)) continue
    if (globs.some((g) => g.re.test(f))) continue
    push('uncovered', '未被任何查点覆盖：' + f)
  }
  for (const p of exact) if (!fsView.exists(p)) push('phantom-path', '覆盖账本里的路径不存在：' + p)

  // 2. 编号一致性
  const tableSet = new Set(doc.tableIds)
  const sectionSet = new Set(doc.sectionIds)
  for (const id of tableSet) if (!sectionSet.has(id)) push('missing-section', '主表有 ' + id + ' 但缺 ### 章节')
  for (const id of sectionSet) if (!tableSet.has(id)) push('missing-row', '章节 ' + id + ' 未登记进主表')
  const dup = doc.tableIds.filter((id, i) => doc.tableIds.indexOf(id) !== i)
  for (const id of new Set(dup)) push('dup-row', '主表重复登记：' + id)

  // 3. 耦合边（右端可以是「外部角色」文字，只有形如查点 ID 的才要求已登记）
  for (const c of doc.couplings) {
    if (!c.parsed) { push('bad-coupling', '耦合边格式应为 `A -> B | 关系 | path:line`：' + c.raw); continue }
    if (!tableSet.has(c.from)) push('bad-coupling', '耦合边左端未登记：' + c.from + '（' + c.raw + '）')
    if (ID_SHAPE.test(c.to) && !tableSet.has(c.to)) push('bad-coupling', '耦合边右端是查点 ID 但未登记：' + c.to + '（' + c.raw + '）')
    const ev = (c.evidence || '').match(EVIDENCE_RE)
    if (!ev) push('bad-coupling', '耦合边缺 `path:line` 证据：' + c.raw)
    else {
      const rel = norm(ev[1])
      const n = fsView.lineCount(rel)
      if (n === null) push('bad-coupling', '耦合边证据文件不存在：' + rel + '（' + c.raw + '）')
      else if (Number(ev[2]) > n) push('bad-coupling', '耦合边证据行号越界：' + rel + ':' + ev[2] + '（共 ' + n + ' 行）')
    }
  }

  // 4. 锚点
  const seen = new Set()
  for (const a of doc.anchors) {
    const key = a.path + ':' + a.line
    if (seen.has(key)) continue
    seen.add(key)
    const n = fsView.lineCount(a.path)
    if (n === null) push('bad-anchor', '锚点文件不存在（apk 仓与协调仓都没有）：' + a.path + ':' + a.line)
    else if (a.line < 1 || a.line > n) push('bad-anchor', '锚点行号越界：' + a.path + ':' + a.line + '（共 ' + n + ' 行）')
  }

  // 5. 排版
  if (EMOJI_RE.test(text)) push('emoji', '文档含 emoji（仓铁律：提交/文档/标签一律不用）')
  let open = false
  for (const line of text.split('\n')) {
    if (line.trim().startsWith('```')) open = !open
  }
  if (open) push('fence', 'mermaid/代码围栏未闭合')
  for (const block of doc.mermaid) {
    const styleHit = block.split('\n').find((l) => /^\s*(style|classDef|linkStyle)\b/.test(l))
    if (styleHit) push('mermaid-style', 'mermaid 块里出现 ' + styleHit.trim() + '（渲染兼容：不用样式指令）')
    const nodes = block.split('\n').filter((l) => /-->|->>|participant |\["/.test(l)).length
    if (nodes < 2) push('mermaid-empty', 'mermaid 块看不出流程（节点/边少于 2 行）')
  }
  return { issues, stats: {
    blocks: tableSet.size, coverage: doc.coverage.length, anchors: seen.size,
    couplings: doc.couplings.length, mermaid: doc.mermaid.length,
  } }
}

/** 收集覆盖全域（仓内实际文件）。 */
function collectUniverse(repo) {
  const files = []
  const walk = (root, keep) => {
    const abs = join(repo, root)
    if (!existsSync(abs)) return
    const rec = (dir) => {
      for (const name of readdirSync(dir)) {
        if (SKIP_DIRS.has(name)) continue
        const full = join(dir, name)
        if (statSync(full).isDirectory()) rec(full)
        else {
          const rel = norm(relative(join(repo, root), full))
          if (keep(rel)) files.push(norm(join(root, rel)))
        }
      }
    }
    rec(abs)
  }
  for (const rule of UNIVERSE) walk(rule.root, rule.keep)
  return files.sort()
}

function makeFsView(repo, parent) {
  const cache = new Map()
  const weak = new Set()
  const locate = (rel) => {
    for (const root of [repo, parent].filter((r) => r !== null)) {
      const abs = join(root, rel)
      if (existsSync(abs)) return abs
    }
    return null
  }
  const lineOf = (abs) => {
    try { return readFileSync(abs, 'utf8').split('\n').length } catch { return null }
  }
  // 裸文件名（正文里常写 `BrowserHost.kt:498`）按全仓同名文件解析：唯一=严格，多候选=弱锚点
  let index = null
  const buildIndex = () => {
    index = new Map()
    const rec = (dir) => {
      let names = []
      try { names = readdirSync(dir) } catch { return }
      for (const name of names) {
        if (SKIP_DIRS.has(name)) continue
        const full = join(dir, name)
        let st = null
        try { st = statSync(full) } catch { continue }
        if (st.isDirectory()) rec(full)
        else {
          const list = index.get(name) ?? []
          list.push(full)
          index.set(name, list)
        }
      }
    }
    rec(repo)
  }
  return {
    weak,
    exists: (rel) => locate(rel) !== null,
    lineCount: (rel) => {
      if (cache.has(rel)) return cache.get(rel)
      let n = null
      const abs = locate(rel)
      if (abs) n = lineOf(abs)
      else if (!rel.includes('/')) {
        if (index === null) buildIndex()
        const cands = index.get(rel) ?? []
        if (cands.length > 0) {
          weak.add(rel)
          n = Math.max(...cands.map(lineOf).filter((x) => typeof x === 'number'))
        }
      }
      cache.set(rel, n)
      return n
    },
  }
}

function selfTest() {
  const universe = ['app/a.kt', 'app/b.kt']
  const mk = (map) => ({
    exists: (p) => Object.prototype.hasOwnProperty.call(map, p),
    lineCount: (p) => (Object.prototype.hasOwnProperty.call(map, p) ? map[p] : null),
  })
  const fsFull = mk({ 'app/a.kt': 10, 'app/b.kt': 20 })
  const head = '<!-- COVERAGE\n'
  const clean = '<!-- TABLE-ROW\n| K01 | 甲 | 一句话 | 入口 | 稳态控制 | - | - | app/a.kt | 低 |\n-->\n'
    + '### K01 甲\n\n- **一句话**：x\n- 关键坐标：`app/a.kt:3`\n\n<!-- COUPLING\nK01 -> K01 | 自环 | app/a.kt:4\n-->\n'
    + head + 'app/a.kt\napp/b.kt\n-->\n'
  const cases = [
    ['干净样例判绿', clean, fsFull, 0],
    ['覆盖缺口能被发现（b.kt 无人认领）', clean.replace('app/b.kt\n-->\n', '-->\n'), fsFull, 1],
    ['幽灵路径能被发现（追加一个不存在的路径）', clean.replace('app/b.kt\n-->\n', 'app/b.kt\napp/none.kt\n-->\n'), fsFull, 1],
    ['锚点行号越界能被发现', clean.replace('app/a.kt:3', 'app/a.kt:99'), fsFull, 1],
    ['锚点文件不存在能被发现', clean.replace('app/a.kt:3', 'app/ghost.kt:3'), fsFull, 1],
    ['主表/章节编号不一致能被发现', clean.replace('### K01 甲', '### K02 甲'), fsFull, 2],
    ['耦合边缺证据能被发现', clean.replace('| 自环 | app/a.kt:4', '| 自环 |'), fsFull, 1],
    ['emoji 能被发现', clean.replace('### K01 甲', '### K01 甲 ' + String.fromCodePoint(0x26A0)), fsFull, 1],
  ]
  let pass = 0
  const lines = []
  for (const [name, text, fsView, want] of cases) {
    const { issues } = checkDoc(text, universe, fsView)
    const ok = issues.length === want
    if (ok) pass += 1
    lines.push((ok ? '  ok   ' : '  FAIL ') + name + '：命中 ' + issues.length + ' / 期望 ' + want
      + (ok ? '' : ' -> ' + issues.map((i) => i.kind).join(',')))
  }
  const ok = pass === cases.length
  console.log((ok ? 'CHECK-CODE-MAP SELF-TEST PASSED' : 'CHECK-CODE-MAP SELF-TEST FAILED'))
  for (const l of lines) console.log(l)
  process.exit(ok ? 0 : 1)
}

// 仅作为主程序执行时跑校验；被 import 时只暴露纯函数（便于探针/其他门禁复用）。
const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMain) {
  if (SELF_TEST) selfTest()

  const docAbs = resolvePath(REPO, DOC_REL)
  if (!existsSync(docAbs)) {
    console.error('CHECK-CODE-MAP FAILED：地图文档不存在：' + DOC_REL + '（先写文档，或 --doc 指定）')
    process.exit(2)
  }
  const text = readFileSync(docAbs, 'utf8')
  const universe = collectUniverse(REPO)
  const { issues, stats } = checkDoc(text, universe, makeFsView(REPO, NO_PARENT ? null : PARENT))

  console.log('执行地图: ' + DOC_REL + '（基线 repo=' + REPO + '）')
  console.log('盘点: 查点 ' + stats.blocks + ' · 覆盖条目 ' + stats.coverage + ' · 锚点 ' + stats.anchors
    + ' · 耦合边 ' + stats.couplings + ' · 流程图 ' + stats.mermaid + ' · 全域文件 ' + universe.length)
  if (LIST_ONLY) {
    const kinds = {}
    for (const i of issues) kinds[i.kind] = (kinds[i.kind] ?? 0) + 1
    console.log('盘点明细（--list 不判红，最多 200 条）：')
    for (const i of issues.slice(0, 200)) console.log('  - [' + i.kind + '] ' + i.detail)
    if (issues.length > 200) console.log('  ... 另有 ' + (issues.length - 200) + ' 条')
    console.log('汇总: ' + (issues.length === 0 ? '无' : JSON.stringify(kinds)))
    process.exit(0)
  }
  if (issues.length > 0) {
    console.error('CHECK-CODE-MAP FAILED（' + issues.length + ' 项）：')
    for (const i of issues.slice(0, 40)) console.error('  - [' + i.kind + '] ' + i.detail)
    if (issues.length > 40) console.error('  ... 另有 ' + (issues.length - 40) + ' 项')
    process.exit(1)
  }
  console.log('CHECK-CODE-MAP PASSED（覆盖完整、锚点有效、编号一致）')
}
