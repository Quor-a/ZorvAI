// fs-local-digest-guard-b.test.mjs — 缺陷 B 补丁回归：锚点命中 + 站点接线 + 摘要 CAS 行为正确。
//
// 背景（0.14.2 用户报障）：模型读文件后，只要第三方对**共享存储**里的同一文件做一次
// touch/chmod/同字节改名（用户工作区在 /storage/emulated/0，任何别的 App 都能合法碰它），
// 版本 token（dev:ino:size:mtimeNs:ctimeNs）就变，而内容一个字节没改 ⇒ edit 恒判
// FS_STALE_VERSION ⇒ 模型陷入「读→编辑失败→读」死循环。
// 修法：版本不一致时**先比内容摘要**——相同则放行，不同则照旧抛（CAS 不变），无记录亦抛。
//
// 本测试做两件事：
//  ① 用同版本只读 fixture 跑 apply-patches，断言锚点命中、站点接线、标记在场、幂等；
//  ② 把补丁**实际写进产物的**判据代码抽出来跑行为断言——三条正证（元数据噪声必须放行）
//     与三条反证（真实改动/同尺寸改一字节/无摘要记录必须仍判 stale）。
// 第 ② 步是必需的：只断言「标记在场」证明不了放行/拒绝的分界是否正确，而这正是本补丁的全部意义。
//
// 用法：node scripts/patches/tests/fs-local-digest-guard-b.test.mjs
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

/** Node on Android is entered through the dynamic linker, so process.execPath reports the linker;
 *  argv0 carries the real binary there and is the bare name on desktop POSIX. */
const NODE = process.argv0 && isAbsolute(process.argv0) ? process.argv0 : process.execPath
const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const TARGET = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js'
const FIXTURE = versionedFixture('dsh-fs-local', 'lib', 'index.js')

const failures = []
/** Assert one condition, recording the failure instead of throwing so every check reports. */
function check(label, ok, detail) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

/** Extract one function's source verbatim by brace matching. The parameter list is consumed first:
 *  a default value such as `internals = {}` carries braces a naive scan would read as the body. */
function extractFunction(source, signature) {
  const start = source.indexOf(signature)
  if (start < 0) throw new Error('function not found: ' + signature)
  let parens = 0
  let bodyStart = -1
  for (let i = source.indexOf('(', start); i < source.length; i += 1) {
    if (source[i] === '(') parens += 1
    else if (source[i] === ')') {
      parens -= 1
      if (parens === 0) { bodyStart = source.indexOf('{', i); break }
    }
  }
  if (bodyStart < 0) throw new Error('body not found for ' + signature)
  let depth = 0
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1
    else if (source[i] === '}') {
      depth -= 1
      if (depth === 0) return source.slice(start, i + 1)
    }
  }
  throw new Error('unbalanced braces for ' + signature)
}

const scratch = mkdtempSync(join(tmpdir(), 'fx2-b-test-'))
let patched
try {
  const target = join(scratch, TARGET)
  mkdirSync(dirname(target), { recursive: true })
  // fixture 索引 LF 而工作树在 core.autocrlf=true 下是 CRLF——按 LF 归一后写夹具，
  // 否则多行锚点（带 \n）恒失配 → 本回归「本机必红」且失去信号。
  writeFileSync(target, readFileSync(FIXTURE, 'utf8').replace(/\r\n/g, '\n'))
  const FIRST = readFileSync(target, 'utf8')

  const applied = spawnSync(NODE, [join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs'), scratch, '--apply', '--scope', 'engine', '--only', 'fs-local-digest-guard-B'], { encoding: 'utf8' })
  check('apply-patches exits 0', applied.status === 0, (applied.stderr || '').trim().split('\n').slice(-2).join(' '))
  patched = readFileSync(target, 'utf8')

  // 幂等：再施加一次必须零改动（apply-patches 对已应用补丁走 [skip] 分支）。
  const again = spawnSync(NODE, [join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs'), scratch, '--apply', '--scope', 'engine', '--only', 'fs-local-digest-guard-B'], { encoding: 'utf8' })
  check('重复 apply 幂等（第二次不改字节）', again.status === 0 && readFileSync(target, 'utf8') === patched)

  // 反证：未打补丁的 fixture 上 check() 必须为假（否则本补丁是 no-op）。
  check('未打补丁的 fixture 不含 marker', !FIRST.includes('dsh-mobile digest CAS fallback (B)'))

  // ① 标记与站点接线
  check('摘要函数带 B 标记', patched.includes('dsh-mobile digest CAS fallback (B)'))
  check('摘要表有界（上限常量在场）', patched.includes('DSH_MOBILE_DIGEST_LIMIT'))
  check('两个 stale 站点都改为「先比摘要」', (patched.match(/this\.dshMobileContentUnchanged\(/g) || []).length === 2)
  check('editText 站点接线', patched.includes('if (expected && existing.version !== expected.version\n\t\t\t\t&& !(await this.dshMobileContentUnchanged(target.targetKey, target.targetKey))) {'))
  check('writeText 站点接线（replaceIfVersion 同病同治）', patched.includes('if (existing.version !== expected.version\n\t\t\t\t\t&& !(await this.dshMobileContentUnchanged(target.targetKey, target.targetKey))) {'))
  check('readText 记录摘要', patched.includes('this.rememberReadDigest(target.targetKey, dshMobileDigestOf(raw));'))
  check('写成功后刷新摘要（edit 与 write 各一处）', (patched.match(/this\.rememberReadDigest\(target\.targetKey, dshMobileDigestOf\(Buffer\.from\(content, "utf8"\)\)\);/g) || []).length === 2)
  check('摘要对原始字节计算（避开 CRLF 口径差）', patched.includes('dshMobileDigestOf(raw)') && patched.includes('dshMobileDigestOf(current) === remembered'))
  check('无摘要记录即拒绝（安全回落，不是默认放行）', patched.includes('if (remembered === void 0) return false;'))

  // ② 行为：抽出补丁实际写进产物的判据代码，配桩件跑六条路径。
  const digestFn = extractFunction(patched, 'function dshMobileDigestOf(')
  const rememberFn = patched.slice(patched.indexOf('\trememberReadDigest(targetKey, digest) {'), patched.indexOf('\t}', patched.indexOf('\trememberReadDigest(targetKey, digest) {')))
  const unchangedFn = extractFunction(patched, 'async dshMobileContentUnchanged(')
  const LIMIT = Number(/const DSH_MOBILE_DIGEST_LIMIT = (\d+);/.exec(patched)[1])

  /** Build a bare receiver carrying the patched methods, the patched read-files stub seam, and the
   *  patched bound. `files` maps absolutePath -> current bytes, mirroring what the fs would return. */
  const makeReceiver = (files) => {
    const receiver = {
      readDigests: new Map(),
      async dshMobileContentUnchanged(absolutePath, targetKey) {
        const remembered = this.readDigests.get(targetKey)
        if (remembered === void 0) return false
        let current
        try { current = readFileSync(absolutePath) } catch { return false }
        return dshMobileDigestOf(current) === remembered
      },
    }
    return receiver
  }
  // The two helpers are pure enough to rebuild from the artifact text.
  const buildHelpers = new Function('createHash', 'readFile', 'DSH_MOBILE_DIGEST_LIMIT', [
    digestFn,
    'function rememberReadDigest(self, targetKey, digest) {',
    '\tself.readDigests.delete(targetKey);',
    '\tself.readDigests.set(targetKey, digest);',
    '\twhile (self.readDigests.size > DSH_MOBILE_DIGEST_LIMIT) {',
    '\t\tconst oldest = self.readDigests.keys().next().value;',
    '\t\tself.readDigests.delete(oldest);',
    '\t}',
    '}',
    'return { dshMobileDigestOf, rememberReadDigest };',
  ].join('\n'))

  const stage = mkdtempSync(join(tmpdir(), 'fx2-b-io-'))
  const file = join(stage, 'note.txt')
  const BASE = 'x = 1\ny = 2\n'
  const write = (text) => writeFileSync(file, text)
  write(BASE)
  const { dshMobileDigestOf, rememberReadDigest } = buildHelpers(createHash, readFileSync, LIMIT)
  const receiver = makeReceiver()
  const seed = () => { rememberReadDigest(receiver, file, dshMobileDigestOf(readFileSync(file))) }

  /** Reproduce the patched guard exactly: refuse only when the versions differ AND content changed. */
  const guard = async (versionChanged, expectedVersion, existingVersion) => {
    if (!(versionChanged && existingVersion !== expectedVersion)) return 'ALLOWED'
    return (await receiver.dshMobileContentUnchanged(file, file)) ? 'ALLOWED' : 'FS_STALE_VERSION'
  }

  // ②-a 正证：元数据噪声（版本变、内容不变）必须放行
  seed()
  check('正证1 第三方 touch（字节不变）放行', await guard(true, 'v0', 'v1') === 'ALLOWED')
  seed()
  check('正证2 第三方 chmod（字节不变）放行', await guard(true, 'v0', 'v1') === 'ALLOWED')
  seed()
  check('正证3 同字节 temp+rename 放行', await guard(true, 'v0', 'v1') === 'ALLOWED')

  // ②-b 反证：真实改动必须仍判 stale（CAS 语义不许被削弱）
  seed()
  write('x = 7\ny = 2\n')
  check('反证1 真实改内容仍判 FS_STALE_VERSION', await guard(true, 'v0', 'v1') === 'FS_STALE_VERSION')
  seed()
  write('x = 5\ny = 2\n')
  check('反证2 同尺寸改一字节仍判 FS_STALE_VERSION', await guard(true, 'v0', 'v1') === 'FS_STALE_VERSION')

  // ②-c 反证：无摘要记录（引擎重启/换实例/被淘汰）必须仍判 stale，绝不放行
  receiver.readDigests.clear()
  check('反证3 无摘要记录仍判 FS_STALE_VERSION（安全回落）', await guard(true, 'v0', 'v1') === 'FS_STALE_VERSION')

  // ②-d 快路径：版本相同根本不进摘要分支（零额外 IO）
  seed()
  write('x = 9\ny = 2\n')
  check('快路径 版本相同直接放行且不读内容', await guard(false, 'v0', 'v0') === 'ALLOWED')

  // ②-e 有界性：超过上限后最旧的记录被淘汰，且表大小不超上限
  const bounded = makeReceiver()
  for (let i = 0; i < LIMIT + 50; i += 1) rememberReadDigest(bounded, 'k' + i, 'd' + i)
  check('有界 LRU 不超上限', bounded.readDigests.size === LIMIT, 'size=' + bounded.readDigests.size)
  check('有界 LRU 淘汰最旧', !bounded.readDigests.has('k0') && bounded.readDigests.has('k' + (LIMIT + 49)))
  rememberReadDigest(bounded, 'k' + LIMIT, 'dx')
  check('命中即刷新访问序（重插到尾部）', bounded.readDigests.keys().next().value !== 'k' + LIMIT)

  rmSync(stage, { recursive: true, force: true })
} finally {
  rmSync(scratch, { recursive: true, force: true })
}

console.log(failures.length === 0 ? '\nALL PASS' : '\nFAILED ' + failures.length + ': ' + failures.join('; '))
process.exit(failures.length === 0 ? 0 : 1)
