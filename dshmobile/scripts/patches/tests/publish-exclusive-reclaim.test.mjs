// publish-exclusive-reclaim.test.mjs — F7 补丁行为回归：F5 两站共用 O_EXCL 占位 + 失败回收。
//
// 背景（apk issue #170 / FX-207.1+207.2）：F5 把 Android 上不可用的 link(2) 回退成 rename()，
// 但 rename 会**静默替换**已存在的目标——上游 link 的 EEXIST 独占语义（并发发布时退回 false）
// 在 Android 上成了死代码。F7 把 O_EXCL 原子占位抽成小函数，F5 的两站共用：
//   ① publish 站：输家得 EEXIST 并 return false；
//   ② materialize 站：输家得 EEXIST 并抛出（该站上游只有抛错通道：persistBatch 把任何 resolve
//      当成 materialized，返回 false 会变成静默无操作）。
// 两站占位成功后任何失败路径都必须 unlink 回收占位——否则 0 字节残留会让之后每次发布都输掉竞争。
//
// 本测试做三件事：① 对只读 fixture 跑 apply-patches（F5+F7），断言两站 marker 与共用小函数在场；
// ② 把打过补丁的两个函数逐字抽出、注入 rename 失败桩，断言占位被回收且下一次发布成功；
// ③ 断言并发输家得到 EEXIST 且**不覆盖**赢家字节。
//
// `--asset <file>`（review C1 新增）：跳过补丁施加，直接对**运行时资产正文本体**跑同一组行为断言
// （0.14.0-preview 的坏资产格式在旧用例下全绿——只测合成补丁输出，测不到已分叉的资产）。
//
// 用法：node scripts/patches/tests/publish-exclusive-reclaim.test.mjs [--asset <asset.js>]
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync, statSync } from 'node:fs'
import { open as realOpen, unlink as realUnlink, rename as realRename, rm as realRm, mkdir as realMkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const TARGET = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js'
const FIXTURE = versionedFixture('dsh-session-persistence-jsonl', 'lib', 'index.js')
const assetIdx = process.argv.indexOf('--asset')
const ASSET = assetIdx >= 0 ? process.argv[assetIdx + 1] : null
// F7 v1 双占位形态（0.14.0-preview 坏资产）：publish 站内联 open("wx") 后又调 helper → 恒 EEXIST → 恒 false。
const LEGACY_INLINE = 'const claim = await open(currentPath, "wx");'

const failures = []
/** Assert one condition, recording the failure instead of throwing so every check reports. */
function check(label, ok, detail) {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

/** Extract one function's source verbatim by brace matching. */
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

const scratch = mkdtempSync(join(tmpdir(), 'f7-test-'))
try {
  let patched
  let parseTarget
  if (ASSET) {
    // 资产本体模式（review C1）：不施加补丁，直接审被 APK 内嵌、启动时覆盖运行树的那份字节。
    patched = readFileSync(ASSET, 'utf8')
    parseTarget = ASSET
    check('资产模式：直接对资产正文本体运行', true)
  } else {
    const target = join(scratch, TARGET)
    mkdirSync(dirname(target), { recursive: true })
    // FX-E19：fixture 索引 LF 而工作树在 core.autocrlf=true 下是 CRLF——按 LF 归一后写夹具。
    writeFileSync(target, readFileSync(FIXTURE, 'utf8').replace(/\r\n/g, '\n'))

    const applied = spawnSync(process.execPath,
      [join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs'), scratch, '--apply', '--scope', 'engine',
        '--only', 'spj-migration-link-F5,publish-exclusive-F7'],
      { encoding: 'utf8' })
    check('apply-patches (F5+F7) exits 0', applied.status === 0,
      (applied.stderr || '').trim().split('\n').slice(-3).join(' '))
    patched = readFileSync(target, 'utf8')
    parseTarget = target
  }

  // ── ① 补丁面断言 ──────────────────────────────────────────────────────────
  check('F5 两站回退 marker 未被回归', (patched.match(/dsh-mobile link->rename fallback/g) || []).length === 2)
  check('publish 站 marker 在场', patched.includes('dsh-mobile exclusive publish (F7)'))
  check('materialize 站 marker 在场', patched.includes('dsh-mobile exclusive materialize (F7)'))
  // review C1：v1 双占位形态（内联占位 + helper 占位并存）必须判红——它在旧用例下全绿但恒失败。
  check('无旧内联占位残留（0.14.0-preview 坏资产形态判红）', !patched.includes(LEGACY_INLINE))
  check('unlink 已导入（回收占位用）', patched.includes('truncate, unlink } from "node:fs/promises"'))
  check('O_EXCL 占位抽成模块级小函数', patched.includes('async function dshMobileClaimExclusive(targetPath) {'))
  check('失败回收抽成模块级小函数', patched.includes('async function dshMobileReleaseClaim(targetPath) {'))
  check('两站共用同一占位函数（调用点 = 2）',
    (patched.match(/await dshMobileClaimExclusive\(/g) || []).length === 2,
    'calls=' + ((patched.match(/await dshMobileClaimExclusive\(/g) || []).length))
  check('两站都带失败回收（调用点 = 2）',
    (patched.match(/await dshMobileReleaseClaim\(/g) || []).length === 2,
    'calls=' + ((patched.match(/await dshMobileReleaseClaim\(/g) || []).length))
  check('独占前提注释写明 flock-android-F3 stub 的后果',
    patched.includes('flock-android-F3 stubbing the writer lock out on Android'))

  const parse = spawnSync(process.execPath, ['--check', parseTarget], { encoding: 'utf8' })
  check('patched file parses', parse.status === 0, (parse.stderr || '').split('\n')[0])

  const publishSrc = extractFunction(patched, 'async function publishCurrentExclusive(')
  const claimSrc = extractFunction(patched, 'async function dshMobileClaimExclusive(')
  const releaseSrc = extractFunction(patched, 'async function dshMobileReleaseClaim(')
  const matSrc = extractFunction(patched, 'async materializePosix(project, dir, finalPath, id, content) {')
  const isEEXIST = (error) => Boolean(error && error.code === 'EEXIST')
  const dirnameOf = (p) => String(p).replace(/[/\\][^/\\]*$/, '')
  const eacces = Object.assign(new Error('EACCES: permission denied, link ...'), { code: 'EACCES' })
  const internals = () => ({ platform: 'linux', fs: { link: async () => { throw eacces } }, publishNewWin32: null })

  // ── ② publish 站行为（真文件系统 + 注入 rename 失败桩）─────────────────────
  const publishFactory = (renameImpl, openImpl = realOpen) => new Function(
    'isEEXIST', 'syncDirectory', 'dirname', 'rename', 'open', 'unlink',
    [claimSrc, releaseSrc, publishSrc, 'return publishCurrentExclusive;'].join('\n'),
  )(isEEXIST, async () => {}, dirnameOf, renameImpl, openImpl, realUnlink)

  // review C1 行为判据：干净目标上的首次发布必须成功（v1 双占位形态在这里恒 return false——
  // 内联 open 先建成 0 字节目标，helper 占位对同一路径必得 EEXIST，且毒占位永久残留）。
  const happyStaged = join(scratch, 'happy-staged')
  const happyCurrent = join(scratch, 'happy-current')
  writeFileSync(happyStaged, 'happy-payload')
  const happy = await publishFactory(async (from, to) => { await realRename(from, to) })(happyStaged, happyCurrent, internals())
  check('首次发布 return true（v1 双占位形态此处恒 false）', happy === true, 'return=' + happy)
  check('首次发布目标内容正确', existsSync(happyCurrent) && readFileSync(happyCurrent, 'utf8') === 'happy-payload')

  const staged = join(scratch, 'staged-payload')
  const current = join(scratch, 'current-publish')
  writeFileSync(staged, 'payload')

  const eio = Object.assign(new Error('EIO: i/o error, rename ...'), { code: 'EIO' })
  let publishError = null
  try {
    await publishFactory(async () => { throw eio })(staged, current, internals())
  } catch (error) { publishError = error }
  check('rename 失败时错误原样抛出', publishError === eio)
  check('失败后占位被回收（currentPath 不存在，非 0 字节残留）', !existsSync(current),
    existsSync(current) ? 'size=' + statSync(current).size : undefined)

  const renamed = []
  const retry = await publishFactory(async (from, to) => { renamed.push([from, to]); await realRename(from, to) })(staged, current, internals())
  check('下一次发布 return 非 false 且成功', retry === true, 'return=' + retry)
  check('重试后目标为本次 payload', existsSync(current) && readFileSync(current, 'utf8') === 'payload')
  check('重试走的是一次 rename', renamed.length === 1, JSON.stringify(renamed))

  // 并发输家：赢家已占住目标 -> 占位得 EEXIST -> return false，且不覆盖赢家字节
  const winnerPath = join(scratch, 'current-winner')
  writeFileSync(winnerPath, 'winner-bytes')
  const loser = await publishFactory(async () => { throw new Error('rename must not run for the loser') })(
    join(scratch, 'loser-staged'), winnerPath, internals())
  check('并发输家 return false（与 link 路径同语义）', loser === false, 'return=' + loser)
  check('并发输家不覆盖赢家字节', readFileSync(winnerPath, 'utf8') === 'winner-bytes')

  // 占位本身的非 EEXIST 错误照常抛出
  const denied = Object.assign(new Error('EACCES: open ...'), { code: 'EACCES' })
  let deniedError = null
  try {
    await publishFactory(realRename, async () => { throw denied })(join(scratch, 's2'), join(scratch, 'c2'), internals())
  } catch (error) { deniedError = error }
  check('占位的非 EEXIST 失败照常抛出', deniedError === denied)

  // ── ③ materialize 站行为（同一小函数；输家得 EEXIST，绝不 rename 覆盖）──────
  // 与真实补丁产物同形：同一作用域里定义两个小函数，materialize 站直接调用它们。
  const buildMaterialize = (renameImpl, linkImpl) => new Function(
    'isEEXIST', 'mkdir', 'link', 'rm', 'rename', 'open', 'unlink', 'dirname',
    [claimSrc, releaseSrc, 'return {' + matSrc + '};'].join('\n'),
  )(isEEXIST, realMkdir, linkImpl, realRm, renameImpl, realOpen, realUnlink, dirnameOf)
  const fakeThis = (root) => ({
    root,
    syncDirPosix: async () => {},
    rejectExistingLog: async () => {},
    writeSyncedTempFile: async (finalPath, content) => {
      const tmp = finalPath + '.tmp-fixture'
      writeFileSync(tmp, content)
      return tmp
    },
  })
  const runMaterialize = (obj, root, finalPath, id, content) =>
    obj.materializePosix.call(fakeThis(root), root, root, finalPath, id, content)

  // 输家：目标已被赢家占住 -> 站内得 EEXIST 抛出；tmp 清理；赢家字节不动
  const matRoot = join(scratch, 'mat-loser')
  mkdirSync(matRoot, { recursive: true })
  const winnerLog = join(matRoot, 'session.jsonl')
  writeFileSync(winnerLog, 'winner-log')
  const matLoser = buildMaterialize(async () => { throw new Error('rename must not run for the loser') },
    async () => { throw eacces })
  let matError = null
  try {
    await runMaterialize(matLoser, matRoot, winnerLog, 'sess-loser', 'loser-log')
  } catch (error) { matError = error }
  check('materialize 站输家得到 EEXIST', Boolean(matError && matError.code === 'EEXIST'),
    matError ? String(matError.code || matError.message) : 'no error')
  check('materialize 站输家不覆盖赢家字节', readFileSync(winnerLog, 'utf8') === 'winner-log')
  check('materialize 站输家清理了自己的临时文件', !existsSync(winnerLog + '.tmp-fixture'))

  // 占位成功但 rename 失败 -> 占位被回收，且下一次发布成功
  const matRoot2 = join(scratch, 'mat-reclaim')
  mkdirSync(matRoot2, { recursive: true })
  const reclaimLog = join(matRoot2, 'session.jsonl')
  const eio2 = Object.assign(new Error('EIO: i/o error, rename ...'), { code: 'EIO' })
  const matFail = buildMaterialize(async () => { throw eio2 }, async () => { throw eacces })
  let matFailError = null
  try {
    await runMaterialize(matFail, matRoot2, reclaimLog, 'sess-io', 'payload-io')
  } catch (error) { matFailError = error }
  check('materialize 站 rename 失败时错误原样抛出', matFailError === eio2)
  check('materialize 站 rename 失败后占位被回收', !existsSync(reclaimLog),
    existsSync(reclaimLog) ? 'size=' + statSync(reclaimLog).size : undefined)

  const matOk = buildMaterialize(async (from, to) => { await realRename(from, to) }, async () => { throw eacces })
  await runMaterialize(matOk, matRoot2, reclaimLog, 'sess-io', 'payload-io')
  check('回收后下一次 materialize 成功', existsSync(reclaimLog) && readFileSync(reclaimLog, 'utf8') === 'payload-io')
  check('materialize 成功路径不留临时文件', !existsSync(reclaimLog + '.tmp-fixture'))
} finally {
  rmSync(scratch, { recursive: true, force: true })
}

console.log(failures.length === 0 ? '\nALL PASS' : '\nFAILED ' + failures.length + ': ' + failures.join('; '))
process.exit(failures.length === 0 ? 0 : 1)
