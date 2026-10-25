// atomic-stale-lock.test.mjs — F4 已退役（0.14.2 rc.2 追版），本文件就地改写为「撤销不变量守卫」。
//
// 为什么退役：上游 rc.2 的 dsh-atomic-write/withFileLock 已自带孤儿锁回收 takeOverExitedLock()，
// 语义比 F4 更严谨（F4 的「二次核验」留 TOCTOU 窗口，上游用带 sha256 的 claim 文件关闭了它，
// 并额外防护「死者的 pid 被活进程复用」）。实测读数见 scripts/patches/apply-patches.mjs
// 的 F4 退役注释与 docs/UPSTREAM-0.1.7-RC2-DELTA-2026-09-26.md。
//
// 为什么还要留这个文件：退役不等于失去守卫。撤销一条补丁的前提是「上游满足它」——
// 这个前提会随上游再次漂移。故本测试直接 import **上游真产物**（engine-tgz 夹具，与快照同源字节）
// 并断言该能力仍在；上游若把回收逻辑删掉/改回「operator action」，本测试判红。
//
// 与旧版的差别（须记住，别误读）：
//   - 旧版跑 apply-patches 施加 F4 再断言补丁行为（锚点命中 + 回收 + 幂等）；
//   - 新版不施加任何补丁，只断言上游原生行为。**不要**把「本测试全绿」当成「F4 还在」。
//
// 用法：node scripts/patches/tests/atomic-stale-lock.test.mjs
import { mkdtempSync, existsSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const FIXTURE = versionedFixture('dsh-atomic-write', 'lib', 'index.js')

const failures = []
/** Assert one condition, recording the failure instead of throwing so every check reports. */
function check(label, ok, detail) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

// 退役前提 ①（源码面）：上游产物里必须仍存在回收函数与重试循环里的调用点。
{
  const src = readFileSync(FIXTURE, 'utf8')
  check('upstream still defines takeOverExitedLock', /function takeOverExitedLock\s*\(/.test(src))
  check('retry loop still calls takeOverExitedLock', /await takeOverExitedLock\(lockPath\)/.test(src))
  check('retry loop still probes holder liveness', /process\.kill\(pid, 0\)/.test(src))
  // 反向：上游若退回「contender never removes an existing lock / orphan recovery is an operator action」，
  // 即退役前提消失，本测试必须判红。
  check('upstream no longer says orphan recovery is an operator action',
    !/orphan recovery is an operator/.test(src))
}

// 退役前提 ②（行为面）：直接 import 上游真产物，验孤儿锁回收与活锁保护。
const scratch = mkdtempSync(join(tmpdir(), 'f4-retired-'))
try {
  const target = join(scratch, 'index.mjs')
  // FX-E19：夹具按 LF 归一后再写，避免 CRLF 工作树上 ESM 解析差异。
  writeFileSync(target, readFileSync(FIXTURE, 'utf8').replace(/\r\n/g, '\n'))
  const mod = await import(pathToFileURL(target).href)

  const statePath = join(scratch, 'state.yaml')
  const lockPath = statePath + '.lock'

  check('lock-free call runs the operation',
    await mod.withFileLock(statePath, async () => 'ran', { waitMs: 200 }) === 'ran')
  check('lock released after the operation', !existsSync(lockPath))

  // 孤儿锁：owner pid 已消失 -> 上游自行回收后成功
  writeFileSync(lockPath, '999999\n', { mode: 0o600 })
  check('upstream recovers an orphaned lock',
    await mod.withFileLock(statePath, async () => 'recovered', { waitMs: 2000 }) === 'recovered')
  check('orphaned lock removed', !existsSync(lockPath))

  // 活锁：owner 是本进程 -> 仍然超时且锁保留（证明回收不会误删活锁）
  writeFileSync(lockPath, String(process.pid) + '\n', { mode: 0o600 })
  let liveError = null
  try { await mod.withFileLock(statePath, async () => 'never', { waitMs: 200 }) } catch (error) { liveError = error.message }
  check('live lock still times out',
    typeof liveError === 'string' && liveError.includes('timed out waiting for the writer lock'))
  check('live lock is kept', existsSync(lockPath))

  // 垃圾内容：不解析 -> 不动锁（上游退回等待-超时语义）
  writeFileSync(lockPath, 'not-a-pid\n', { mode: 0o600 })
  let garbageError = null
  try { await mod.withFileLock(statePath, async () => 'never', { waitMs: 200 }) } catch (error) { garbageError = error.message }
  check('unparseable lock is left alone',
    typeof garbageError === 'string' && existsSync(lockPath))
} finally {
  rmSync(scratch, { recursive: true, force: true })
}

if (failures.length > 0) {
  console.error('\natomic-stale-lock (retired-invariant guard): ' + failures.length + ' check(s) failed: ' + failures.join('; '))
  process.exit(1)
}
console.log('\natomic-stale-lock: all checks passed (F4 retired on rc.2; upstream capability still guarded)')
