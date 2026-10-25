// scripts/lib/product-freshness.mjs — 插件产物新鲜度的**内容**判据（0.14.1 W1 收口）
//
// 问题（本轮实锤）：原判据「任一 src/*.ts 比 lib/*.js 新即视为产物过期」是**纯 mtime**。
// robocopy 镜像（铁律 5）、git 检出、编辑器触碰都会把 src 的 mtime 推到 lib 之后，而**产物内容
// 并不陈旧**：实测 plugins/dsh-android-bridge 重建后 lib/index.js 与 lib/client.js 逐字节不变
// （`e7b2fdab…` / `2c833173…`），判据照样命中。两种处置都有代价：
//   - 判 SKIP ⇒ 该插件本轮**完全不被校验**（check-tool-output-schema 的现行处置，实测 bridge 长期落此）；
//   - 判红   ⇒ 把别人的瞬时编辑态当缺陷，阻塞整条门禁链（check-protocol-v2 的现行处置）。
// 更要命的是它**结构上无法区分**「src 变了没重建」与「只是被碰过」——而前者是真缺陷（旧产物判绿 = 假绿）。
//
// 处方：mtime 命中只作**触发**，真伪由**重建哈希**裁决——
//   1) 用该插件自己的 tsconfig 重跑 tsc，只覆盖输出目录与声明产物开关（不改其它编译选项），
//      输出到临时目录，**不碰工作树**；
//   2) 重建产物逐个与 lib/ 同名文件比字节：
//        全部一致      → fresh —— 视为新鲜，按正常路径继续校验（不再 SKIP）；
//        有内容不同    → stale —— 判红：产物与源码不符，先重建再谈其它；
//        编译产物在 lib/ 无对应物 / 工具链缺席 / 编译失败 → unknown —— 重建不可裁决，如实 SKIP。
//
// 为什么是「重建」而不是「记录源码哈希」：记哈希要么写进产物（改镜像面产物字节）、要么另存台账；
// 台账自身会过期，而「src 变了忘更新台账」与「src 变了忘重建」是**同一个错误**——那种台账只能
// 把缺陷换个地方藏。重建是唯一自证的判据：它直接回答「按现在的源码编译，还得到同一份 lib 吗」。
//
// 判据的边界（诚实声明）：它比对 tsc 能产出的那一层（lib/*.js）。插件里由**其它构建步骤**产出的
// 文件（例如 bridge 的 `build-client.mjs` → lib/client.js）不参与裁决——lib/ 多出来的文件不算差异，
// 但重建产物在 lib/ 缺席即视为不可裁决（unknown），绝不猜。
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, relative, resolve } from 'node:path'
import { spawnSync } from 'node:child_process'

/** 递归收集目录下的 .js（不含 .d.ts / .js.map）。 */
const collectJs = (dir, prefix = '') => {
  const out = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const relPath = prefix ? prefix + '/' + name : name
    if (statSync(full).isDirectory()) { out.push(...collectJs(full, relPath)); continue }
    if (name.endsWith('.js')) out.push(relPath)
  }
  return out
}

const newest = (dir, ext) => {
  let ms = 0
  let file = ''
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) continue
    if (!name.endsWith(ext)) continue
    const t = statSync(full).mtimeMs
    if (t > ms) { ms = t; file = name }
  }
  return { ms, file }
}

/**
 * mtime 触发层：src 是否比 lib 新。
 *
 * @param pluginDir - 插件根目录（含 src/ 与 lib/）。
 * @returns `{ trigger, s, l }`；`trigger=true` 表示 mtime 命中（**尚不能**据此判定陈旧，
 *          必须再过 `arbitrateFreshness`）。src 或 lib 缺席时 trigger=false（由调用方按各自
 *          既有纪律处理「产物缺席」）。
 */
export const stalenessTrigger = (pluginDir) => {
  const srcDir = join(resolve(pluginDir), 'src')
  const libDir = join(resolve(pluginDir), 'lib')
  if (!existsSync(srcDir) || !existsSync(libDir)) return { trigger: false, s: null, l: null }
  const s = newest(srcDir, '.ts')
  const l = newest(libDir, '.js')
  return { trigger: s.ms > l.ms, s, l }
}

/** 定位插件的 tsc（自带 node_modules 优先，回落仓库根）。 */
const findTsc = (pluginDir, root) => {
  for (const cand of [
    join(pluginDir, 'node_modules', 'typescript', 'bin', 'tsc'),
    join(root, 'node_modules', 'typescript', 'bin', 'tsc'),
  ]) {
    if (existsSync(cand)) return cand
  }
  return null
}

/** 进程内记忆：同一次门禁运行里，同一插件树只会被裁决一次（调用方会为 loaded/error 两态各驱动一次）。 */
const arbitrationCache = new Map()

/**
 * 裁决层：按现在的源码重建，产物是否还在同一处。
 *
 * @param pluginDir - 插件根目录。
 * @param opts - `{ root }`：仓库根（用于回落 tsc）；缺省由 pluginDir 上溯两级推断。
 * @returns `{ verdict: 'fresh'|'stale'|'unknown', detail, differences }`。
 */
export const arbitrateFreshness = (pluginDir, opts = {}) => {
  const dir = resolve(pluginDir)
  const cached = arbitrationCache.get(dir)
  if (cached !== undefined) return cached
  const verdict = arbitrateUncached(dir, opts)
  arbitrationCache.set(dir, verdict)
  return verdict
}

const arbitrateUncached = (dir, opts) => {
  const root = resolve(opts.root ?? join(dir, '..', '..'))
  const tsconfig = opts.tsconfig ?? join(dir, 'tsconfig.json')
  if (!existsSync(tsconfig)) return { verdict: 'unknown', detail: '无 tsconfig.json（无从重建）', differences: [] }
  const tsc = findTsc(dir, root)
  if (tsc === null) return { verdict: 'unknown', detail: '本机无 typescript（先在该插件目录 npm install）', differences: [] }
  const libDir = join(dir, 'lib')
  let out
  try {
    out = mkdtempSync(join(tmpdir(), 'dsh-fresh-'))
  } catch (e) {
    return { verdict: 'unknown', detail: '临时目录不可建：' + String(e && e.message), differences: [] }
  }
  try {
    // 只覆盖**输出位置**：其余编译选项一律沿用插件自己的 tsconfig，否则比对的就不是「同一份编译」了。
    // 路径类选项要**一并**改到临时目录——只改 outDir 会让 `declarationDir: lib/types` 照旧写进工作树
    // （门禁不得改动被它校验的树），而单独把 declaration 关掉又会撞 TS5069（declarationDir 不能
    // 脱离 declaration/composite 存在，本轮实测）。故走「全部指向临时目录」这条路。
    const r = spawnSync(process.execPath, [
      tsc, '-p', tsconfig,
      '--outDir', out,
      '--declarationDir', join(out, 'types'),
      '--tsBuildInfoFile', join(out, 'tsconfig.tsbuildinfo'),
      '--incremental', 'false',
    ], { encoding: 'utf8', cwd: dir })
    if (r.status !== 0) {
      const raw = ((r.stdout ?? '') + (r.stderr ?? '')).split('\n').filter((l) => l.trim() !== '')
      const firstErr = raw.find((l) => /error TS/.test(l)) ?? raw[0] ?? ''
      return { verdict: 'unknown', detail: '重建失败（tsc 退出码 ' + r.status + '）：' + firstErr.trim().slice(0, 160), differences: [] }
    }
    let emitted
    try {
      emitted = collectJs(out)
    } catch (e) {
      return { verdict: 'unknown', detail: '重建产物不可枚举：' + String(e && e.message), differences: [] }
    }
    if (emitted.length === 0) return { verdict: 'unknown', detail: 'tsc 未产出任何 .js（emitDeclarationOnly / 空 include？）', differences: [] }
    const absent = []
    const differences = []
    for (const relPath of emitted) {
      const built = join(out, relPath)
      const shipped = join(libDir, relPath)
      if (!existsSync(shipped)) { absent.push(relPath); continue }
      if (!readFileSync(built).equals(readFileSync(shipped))) differences.push(relPath)
    }
    if (absent.length > 0) {
      return { verdict: 'unknown', detail: '重建产物在 lib/ 缺席：' + absent.slice(0, 4).join(', '), differences: [] }
    }
    if (differences.length > 0) {
      return { verdict: 'stale', detail: '重建后 ' + differences.length + ' 个产物内容不同：' + differences.slice(0, 4).join(', '), differences }
    }
    return { verdict: 'fresh', detail: '按现源码重建，' + emitted.length + ' 个产物逐字节一致', differences: [] }
  } finally {
    try { rmSync(out, { recursive: true, force: true }) } catch { /* 临时目录清理失败不影响判据 */ }
  }
}

/** 供门禁日志用的短路径。 */
export const shortPath = (root, p) => relative(root, p).replace(/\\/g, '/')
